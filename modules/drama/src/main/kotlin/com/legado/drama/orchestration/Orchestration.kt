package com.legado.drama.orchestration

import com.legado.drama.AppGraph
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.engine.gate.BudgetGuard
import com.legado.drama.engine.gate.FidelityGate
import com.legado.drama.engine.gate.StoryboardReport
import com.legado.drama.engine.gate.ValidationIssue
import com.legado.drama.engine.model.ShotMeta
import com.legado.drama.engine.orchestrator.GateEvaluationLogic
import com.legado.drama.engine.orchestrator.GateReport
import com.legado.drama.engine.orchestrator.PipelineOrchestrator
import com.legado.drama.engine.orchestrator.PipelineStage
import com.legado.drama.engine.provider.PollResult
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.VideoProvider
import com.legado.drama.engine.provider.VideoSubmitRequest
import com.legado.drama.engine.queue.CheckpointStore
import com.legado.drama.engine.queue.PollPolicy
import com.legado.drama.engine.queue.QueueSnapshot
import com.legado.drama.engine.queue.RenderQueue
import com.legado.drama.engine.queue.ShotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 渲染队列实现（架构文档 §7 状态机）：
 * 单消费者协程：pending→submitted→completed/failed/blocked。
 * - 出队前：评审全过（F04）→ load-or-merge 恢复 → 先 repoll 已提交 → 渲染 pending
 * - SUBMITTED 记 video_id 防重复付费；429 长退避由 Provider 承担；预算超/401 → pause
 * - 快照经 StateFlow 直通 UI/通知栏
 */
class DefaultRenderQueue(
    private val videoProvider: VideoProvider,
    private val checkpoint: CheckpointStore,
    private val budget: BudgetGuard,
    private val shotDao: com.legado.drama.data.dao.ShotDao,
    private val episodeDao: com.legado.drama.data.dao.EpisodeDao,
    private val renderTaskDao: com.legado.drama.data.dao.RenderTaskDao,
    private val scope: CoroutineScope,
    private val filesDirProvider: () -> java.io.File = { java.io.File(".") },
    /** keyframes 首尾帧解析：assetId → 渲染用 URI（data uri/本地文件），缺失返回 null 降级为单帧 */
    private val assetUriResolver: (assetId: String) -> String? = { null },
    /** 提交前忠实性二次闸门（HANDOVER §3.1：单镜出队前复核台词/资产，fail-closed） */
    private val fidelityGate: FidelityGate? = null,
    /** 项目资产白名单（FidelityGate 资产原样复核用；空集表示未加载白名单则跳过资产项） */
    private val assetIdsInProject: () -> Set<String> = { emptySet() },
) : RenderQueue {

    private val _state = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> = _state

    private var consumerJob: Job? = null
    @Volatile private var pausedReason: String? = null
    @Volatile private var currentEpisodeId: String? = null
    /** P1-5 对齐源工程：用户对 budget 确认放行后，越过预算门提交一次（提交即复位） */
    @Volatile private var budgetConfirmed = false

    /** 取回失败计数器：单消费者协程内访问，无需并发容器（P0-FIX F5） */
    private val fetchFails = mutableMapOf<String, Int>()

    /** 单镜完成回调（通知栏/UI 使用） */
    var onShotCompleted: ((shotId: String) -> Unit)? = null

    override suspend fun enqueueEpisode(episodeId: String) {
        currentEpisodeId = episodeId
        consumerJob?.cancel()
        consumerJob = scope.launch { consumeLoop(episodeId) }
    }

    private suspend fun consumeLoop(episodeId: String) {
        val episode = episodeDao.get(episodeId) ?: return
        val projectId = episode.projectId
        pausedReason = null

        // ① F04 花钱前人工闸门：资产评审全过才允许渲染
        if (!episode.reviewPassed) {
            setPaused("review")
            _state.value = _state.value.copy(lastMessage = "资产评审未全过，不允许进入渲染")
            return
        }
        // ② 断点续传：load-or-merge
        val shots = shotDao.listByEpisode(episodeId).map { it.toShotMeta() }
        if (shots.isEmpty()) {
            setPaused("noshots")
            _state.value = _state.value.copy(lastMessage = "本集没有分镜")
            return
        }
        val cp = checkpoint.loadOrMerge(episodeId, shots)
        refreshSnapshot(episodeId, shots, "断点续传：已恢复 ${cp.entries.size} 镜")

        // ③ 先轮询已提交（绝不重复 submit，防重复付费）
        for (entry in cp.toRePoll) {
            repoll(entry.shotId, entry.providerTaskId.orEmpty(), entry.episodeId.ifEmpty { episodeId })
            // P1-5：repoll 内 auth/network 暂停后立即终止本轮循环，
            // 防止末尾 setPaused(null) 覆盖暂停状态（最后一镜场景）
            if (pausedReason != null) return
        }
        // ④ 渲染 pending
        for (shot in shots.filter { it.shotId in cp.pendingIds }) {
            if (!scope.isActive) return
            pausedReason?.let { return }
            if (!budget.canSubmit(projectId) && !budgetConfirmed) {
                setPaused("budget")
                return
            }
            renderShot(shot, episodeId, projectId)
            // P1-5：renderShot 内 auth/network 暂停后同样立即终止，
            // 否则最后一镜结束后 setPaused(null) 会把暂停清掉（恢复入口丢失）
            if (pausedReason != null) return
        }
        if (pausedReason == null) {
            setPaused(null)
            refreshSnapshot(episodeId, shots, "全部镜渲染完成")
        }
    }

    private suspend fun repoll(shotId: String, taskId: String, episodeId: String) {
        if (taskId.isEmpty()) {
            checkpoint.markFailed(shotId, "缺少 video_id")
            return
        }
        // 自适应轮询（P0-FIX F10/HANDOVER F1）：submitted 后前 10 分钟每 30s，之后每 60s
        val submittedAt = renderTaskDao.listByEpisode(episodeId)
            .firstOrNull { it.shotId == shotId }?.submittedAt
        var rounds = 0
        while (rounds < MAX_POLL_ROUNDS) {
            rounds++
            try {
                when (val r = videoProvider.pollResult(taskId)) {
                    is PollResult.Completed -> {
                        try {
                            val clipPath = persistClip(r.videoUrl, shotId)
                            checkpoint.markCompleted(shotId, clipPath)
                            onShotCompleted?.invoke(shotId)
                        } catch (fetch: Throwable) {
                            // 取回失败计数（P0-FIX F5）：达上限本轮退出并保留 submitted，待断点续传再试
                            val fails = (fetchFails[shotId] ?: 0) + 1
                            fetchFails[shotId] = fails
                            if (PollPolicy.shouldGiveUpAfterFetchFails(fails)) {
                                _state.value = _state.value.copy(
                                    lastMessage = "镜 $shotId 取回失败 ${PollPolicy.FETCH_RETRY_MAX} 次，保留已提交待续传",
                                )
                                return
                            }
                        }
                        return
                    }
                    is PollResult.Failed -> {
                        checkpoint.markFailed(shotId, r.reason)
                        return
                    }
                    is PollResult.InProgress -> Unit // 按自适应间隔等待后重试
                }
            } catch (e: ProviderError.AuthError) {
                setPaused("auth")
                return
            } catch (e: ProviderError.QuotaError) {
                setPaused("network")
                return
            } catch (_: Throwable) {
                // 弱网：保留 submitted 态，留给断点续传
                return
            }
            delay(PollPolicy.adaptivePollIntervalMs(submittedAt, System.currentTimeMillis()))
        }
        // 轮询多轮仍 InProgress：保留 submitted，交给下次断点续传（不重复付费）
    }

    private suspend fun renderShot(shot: ShotMeta, episodeId: String, projectId: String) {
        val script = episodeDao.get(episodeId)?.scriptJson.orEmpty()
        val checker = StoryboardChecker.provider
        if (checker != null) {
            val report = checker(script, listOf(shot))
            if (!report.passed) {
                val issueSummary = report.issues
                    .filter { it.level == ValidationIssue.Level.ERROR }
                    .joinToString(";") { "镜${it.shotNo} ${it.message}" }
                checkpoint.markFailed(shot.shotId, "六铁律未过: $issueSummary")
                return
            }
        }
        // ★ 提交前忠实性二次闸门（HANDOVER §3.1 FidelityGate）：六铁律在分镜生成后校验，
        // 此处单镜出队前再查台词逐字忠实 + 资产原样，防渲染期间剧本/资产漂移，fail-closed
        fidelityGate?.let { gate ->
            val fr = gate.checkShot(shot, script, assetIdsInProject)
            if (!fr.passed) {
                val issueSummary = fr.issues
                    .filter { it.level == ValidationIssue.Level.ERROR }
                    .joinToString(";") { "镜${it.shotNo} ${it.message}" }
                checkpoint.markFailed(shot.shotId, "忠实性未过: $issueSummary")
                refreshSnapshot(episodeId, shotsFromDao(episodeId), "镜 ${shot.shotNo} 忠实性复核未过，未提交")
                return
            }
        }
        try {
            // keyframes 双帧：资产真实 URI（data uri/本地文件）；缺失时降级单帧由 Provider 自行处理
            val firstUri = shot.firstAssetIds.firstNotNullOfOrNull { assetUriResolver(it) }
            val lastUri = shot.lastAssetIds.firstNotNullOfOrNull { assetUriResolver(it) }
            val taskId = videoProvider.submitVideo(
                VideoSubmitRequest(
                    shotId = shot.shotId,
                    prompt = buildShotPrompt(shot),
                    firstImageUri = firstUri,
                    lastImageUri = lastUri,
                ),
            )
            // ★ SUBMITTED + video_id 即刻落库（防重复付费）
            checkpoint.markSubmitted(shot.shotId, taskId)
            budget.consumeSubmitted(projectId)
            // P1-5：确认放行只对下一镜有效（一次性），提交后即复位防无限越权
            budgetConfirmed = false
            repoll(shot.shotId, taskId, episodeId)
        } catch (e: ProviderError.AuthError) {
            checkpoint.markFailed(shot.shotId, "Key 无效")
            setPaused("auth")
        } catch (e: ProviderError.QuotaError) {
            setPaused("network")
        } catch (e: ProviderError.ValidationError) {
            checkpoint.markFailed(shot.shotId, "参数被拒: ${e.message}")
        } catch (e: Throwable) {
            checkpoint.markFailed(shot.shotId, e.message ?: "未知异常")
        }
    }

    private suspend fun refreshSnapshot(episodeId: String, shots: List<ShotMeta>, msg: String) {
        val tasks = renderTaskDao.listByEpisode(episodeId)
        _state.value = QueueSnapshot(
            episodeId = episodeId,
            total = shots.size,
            completed = tasks.count { it.state == ShotState.COMPLETED.name },
            failed = tasks.count { it.state == ShotState.FAILED.name },
            blocked = tasks.count { it.state == ShotState.BLOCKED.name },
            pending = tasks.count { it.state == ShotState.PENDING.name },
            submittedInFlight = tasks.count { it.state == ShotState.SUBMITTED.name },
            pausedReason = pausedReason,
            lastMessage = msg,
        )
    }

    private fun setPaused(reason: String?) {
        pausedReason = reason
        _state.value = _state.value.copy(pausedReason = reason)
    }

    /**
     * 单镜 mp4 落盘（架构文档 §7.3：markCompleted 记本地文件）：
     * 下载到 filesDir/clips/{shotId}.mp4，字节数>0 才算完成。
     * 下载失败上抛（保留 SUBMITTED 态给断点续传重轮询，不 markFailed，防重复付费）。
     */
    private fun persistClip(url: String, shotId: String): String {
        val dir = java.io.File(filesDirProvider(), "clips").apply { mkdirs() }
        val out = java.io.File(dir, "$shotId.mp4")
        if (out.exists() && out.length() > 0) return out.absolutePath
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "legado-drama/1.0")
        }
        try {
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() <= 0L) {
            out.delete()
            throw IllegalStateException("下载 0 字节")
        }
        return out.absolutePath
    }

    private fun buildShotPrompt(shot: ShotMeta): String = buildString {
        shot.dialogue?.let { append("台词：$it。") }
        shot.narration?.let { append("旁白：$it。") }
        shot.action?.let { append("动作：$it。") }
    }

    override suspend fun pause(reason: String) {
        setPaused(reason)
        consumerJob?.cancel()
    }

    override suspend fun resume(confirmedByUser: Boolean) {
        // P1-5 对齐源工程（budget_exceeded 语义）：预算暂停必须用户显式确认才放行
        if (pausedReason == "budget") {
            if (!confirmedByUser) return
            // 确认放行 → 允许越过预算门提交一次（防确认后再次被 canSubmit 拦回成死循环）
            budgetConfirmed = true
        }
        pausedReason = null
        currentEpisodeId?.let { enqueueEpisode(it) }
    }

    override fun cancelShot(shotId: String) {
        scope.launch { checkpoint.markFailed(shotId, "用户取消") }
    }

    /** 供快照刷新取当前分镜（忠实性阻断后同步 UI） */
    private suspend fun shotsFromDao(episodeId: String): List<ShotMeta> =
        shotDao.listByEpisode(episodeId).map { it.toShotMeta() }

    companion object {
        /** 单镜单次入队最多轮询轮数：30s×2 + 60s×2 ≈ 3 分钟；未完成则保留 submitted 等断点续传 */
        private const val MAX_POLL_ROUNDS = 4
    }
}

/** 六铁律检查器桥接（由 AppGraph 注入，避免 DefaultRenderQueue 强依赖） */
object StoryboardChecker {
    typealias Checker = suspend (script: String, shots: List<ShotMeta>) -> StoryboardReport
    @Volatile var provider: Checker? = null

    fun needCheck(): Checker? = provider
}

/** 七阶段编排器实现 */
class DefaultPipelineOrchestrator(
    private val graph: AppGraph,
    private val shotDao: com.legado.drama.data.dao.ShotDao,
    private val episodeDao: com.legado.drama.data.dao.EpisodeDao,
) : PipelineOrchestrator {

    private val _stage = MutableStateFlow(PipelineStage.S1_PROJECT)
    override val stage: StateFlow<PipelineStage> = _stage

    override suspend fun evaluateGates(projectId: String): GateReport {
        val assets = graph.db.assetDao().listByProject(projectId)
        val assetsGenerated = assets.isNotEmpty() && assets.any { it.g1State == "pass" }
        val reviewPassed = assets.isNotEmpty() && assets.all { it.reviewState == "keep" }
        val keyValid = graph.keyVault.masked("agnes").isNotEmpty() || graph.keyVault.masked("deepseek").isNotEmpty()
        val episodes = episodeDao.listByProject(projectId)
        val storyboardPassed = runCatching {
            episodes.all { ep ->
                val shots = shotDao.listByEpisode(ep.episodeId).map { it.toShotMeta() }
                graph.storyboardGate.check(ep.scriptJson.orEmpty(), shots).passed
            }
        }.getOrDefault(false)
        val budgetOk = true // 实时条数判定由消费端预算闸门承担
        return GateEvaluationLogic.compose(
            assetsGenerated, reviewPassed, storyboardPassed, keyValid, budgetOk,
        )
    }

    override suspend fun advanceTo(stage: PipelineStage) {
        _stage.value = stage
    }

    override suspend fun recoverOnBoot() {
        // 进程重启恢复（架构文档 §7.3 恢复语义 + 零重复付费）：
        // 扫描 render_tasks 中 SUBMITTED/PENDING 的剧集 → 续跑渲染队列。
        // DefaultRenderQueue 消费端 load-or-merge 会把 SUBMITTED 的 video_id 重新入队 re-poll
        // （绝不重新 submit）；COMPLETED 但文件缺失/size=0 重置 PENDING；FAILED/BLOCKED 保持权威态。
        val episodes = episodeDao.listAll()
        for (ep in episodes) {
            val tasks = graph.db.renderTaskDao().listByEpisode(ep.episodeId)
            val needsRecover = tasks.any {
                it.state == com.legado.drama.engine.queue.ShotState.SUBMITTED.name ||
                    it.state == com.legado.drama.engine.queue.ShotState.PENDING.name
            }
            if (needsRecover) {
                graph.renderQueue.enqueueEpisode(ep.episodeId)
            }
        }
    }
}

// ── 实体 → 引擎模型映射 ──
fun ShotEntity.toShotMeta() = ShotMeta(
    shotId = shotId,
    episodeId = episodeId,
    projectId = projectId,
    shotNo = shotNo,
    dialogue = dialogue,
    narration = narration,
    action = action,
    firstAssetIds = decodeIdList(firstAssetIds),
    lastAssetIds = decodeIdList(lastAssetIds),
)

fun EpisodeEntity.zippedEpNo(): Int = epNo

fun decodeIdList(json: String): List<String> = runCatching {
    Json.decodeFromString<List<String>>(json)
}.getOrDefault(emptyList())