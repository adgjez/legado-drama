package com.legado.drama.orchestration

import com.legado.drama.AppGraph
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.engine.gate.BudgetGuard
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
import com.legado.drama.engine.queue.QueueSnapshot
import com.legado.drama.engine.queue.RenderQueue
import com.legado.drama.engine.queue.ShotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) : RenderQueue {

    private val _state = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> = _state

    private var consumerJob: Job? = null
    @Volatile private var pausedReason: String? = null
    @Volatile private var currentEpisodeId: String? = null

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
        }
        // ④ 渲染 pending
        for (shot in shots.filter { it.shotId in cp.pendingIds }) {
            if (!scope.isActive) return
            pausedReason?.let { return }
            if (!budget.canSubmit(projectId)) {
                setPaused("budget")
                return
            }
            renderShot(shot, episodeId, projectId)
        }
        setPaused(null)
        refreshSnapshot(episodeId, shots, "全部镜渲染完成")
    }

    private suspend fun repoll(shotId: String, taskId: String, episodeId: String) {
        if (taskId.isEmpty()) {
            checkpoint.markFailed(shotId, "缺少 video_id")
            return
        }
        try {
            when (val r = videoProvider.pollResult(taskId)) {
                is PollResult.Completed -> {
                    val clipPath = persistClip(r.videoUrl, shotId)
                    checkpoint.markCompleted(shotId, clipPath)
                    onShotCompleted?.invoke(shotId)
                }
                is PollResult.Failed -> checkpoint.markFailed(shotId, r.reason)
                is PollResult.InProgress -> Unit // 保留 submitted，续传再轮询
            }
        } catch (e: ProviderError.AuthError) {
            setPaused("auth")
        } catch (e: ProviderError.QuotaError) {
            setPaused("network")
        } catch (_: Throwable) {
            // 弱网：保留 submitted 态，留给断点续传
        }
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
        try {
            val taskId = videoProvider.submitVideo(
                VideoSubmitRequest(
                    shotId = shot.shotId,
                    prompt = buildShotPrompt(shot),
                    firstImageUri = shot.firstAssetIds.firstOrNull(),
                    lastImageUri = shot.lastAssetIds.firstOrNull(),
                ),
            )
            // ★ SUBMITTED + video_id 即刻落库（防重复付费）
            checkpoint.markSubmitted(shot.shotId, taskId)
            budget.consumeSubmitted(projectId)
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
        if (pausedReason == "budget" && !confirmedByUser) return
        pausedReason = null
        currentEpisodeId?.let { enqueueEpisode(it) }
    }

    override fun cancelShot(shotId: String) {
        scope.launch { checkpoint.markFailed(shotId, "用户取消") }
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
        // 进程重启恢复由 DefaultRenderQueue 的 load-or-merge 承担，
        // 遍历 AI 全托管剧集找出未完成集（消费端知道如何续传）
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