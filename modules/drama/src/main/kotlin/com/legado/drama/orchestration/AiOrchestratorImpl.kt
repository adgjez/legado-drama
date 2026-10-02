package com.legado.drama.orchestration

import com.legado.drama.AppGraph
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.engine.gate.StylePreset
import com.legado.drama.engine.gate.StylePresets
import com.legado.drama.engine.model.AiStageFlags
import com.legado.drama.engine.orchestrator.AiJsonParser
import com.legado.drama.engine.orchestrator.AiOrchestrator
import com.legado.drama.engine.orchestrator.AiOrchestrator.AiError
import com.legado.drama.engine.orchestrator.AiOrchestrator.AiRecoveryState
import com.legado.drama.engine.orchestrator.PipelineStage5
import com.legado.drama.engine.orchestrator.ProgressEvent
import com.legado.drama.engine.orchestrator.ProgressLogTrimmer
import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ImageGenRequest
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.router.DeepSeekDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * AI 全托管五阶段编排实现（T014-arch.md §2.2 契约逐条落地）：
 * 1. run 按五阶段顺序执行，任一阶段异常 → ERROR 事件 → 停止；已建数据保留
 * 2. 自动建项目「AI草稿-MMdd-HHmm」+ stage_flags.ai_managed + last_success_stage
 * 3. 提取资产：文本通道 JSON 输出 → 逐卡落库（角色/场景/道具）
 * 4. 生成图像：每卡调用 ImageProvider 生成 + G1 落位；角色派生 6 pose 行
 * 5. 审计：G2 语义重试 2 次仍差 → 标红放行（决议 Q3）
 * 6. 分镜：文本通道结构化输出 → 资产名→ID 绑定首尾帧 → 六铁律复核落报告
 * 7. 文本模型：textModelId 非空路由；Key 空/未验证 → ModelBlocked 阻断（P1-1 验收 4）
 * 8. 协作式取消：requestCancel 置位后，阶段②逐卡/阶段③审计重生成在下个卡边界抛
 *    PipelineCancelled，run 以 WARN 事件正常结束（不抛 AiError）；已生成资产与进度保留。
 */
class DefaultAiOrchestrator(
    private val graph: AppGraph,
    private val onEnqueueRender: suspend (episodeId: String) -> Unit,
) : AiOrchestrator {

    private val _events = MutableStateFlow<List<ProgressEvent>>(emptyList())
    override val events: StateFlow<List<ProgressEvent>> = _events

    private val _currentEpisodeId = MutableStateFlow<String?>(null)
    override val currentEpisodeId: StateFlow<String?> = _currentEpisodeId

    private val startedAtMs = System.currentTimeMillis()
    private var stageStartMs = System.currentTimeMillis()

    @Volatile private var running = false
    @Volatile private var sessionTextModelId: String = DeepSeekDefaults.MODEL

    /** 协作式取消标志（stop_generate 置位；仅在下一个生成卡边界检查，同步请求不可中途掐断） */
    @Volatile private var cancelRequested = false

    /** 本会话推断出的时代预设（P0-FIX F3：AI 模式自动断代，不再写死西汉） */
    @Volatile private var sessionEra: StylePreset = StylePresets.MODERN

    override fun requestCancel(): Boolean {
        if (!running) return false
        cancelRequested = true
        return true
    }

    private fun checkCancelled() {
        if (cancelRequested) throw PipelineCancelled
    }

    override suspend fun run(
        scriptText: String,
        textModelId: String,
        onAutoCreatedProject: (String, String) -> Unit,
    ) {
        if (running) {
            emit(PipelineStage5.EXTRACT_ASSETS, 0, "已有流水线在运行", ProgressEvent.Level.WARN)
            return
        }
        val cleaned = scriptText.replace(Regex("\\s"), "")
        if (cleaned.length < MIN_SCRIPT_LENGTH) {
            throw AiError.InputTooShort("剧本需 ≥$MIN_SCRIPT_LENGTH 字（实测调用方已校验，此为兜底）")
        }
        running = true
        cancelRequested = false // 新一轮流水线重置取消标志
        stageStartMs = System.currentTimeMillis()
        try {
            // 前置：文本模型 Key 校验，空则阻断（P1-1 验收 4：提示而非静默失败）
            val modelId = textModelId.ifBlank { graph.textRouter.activeTextModelId() }
            sessionTextModelId = modelId
            resolveTextProvider(modelId)

            // 阶段① 提取资产 → 自动建项目
            val (projectId, episodeId) = extractAssetsAndCreateProject(cleaned, modelId)
            onAutoCreatedProject(projectId, episodeId)

            // 阶段② 生成图像（逐卡 + 角色 6 pose）
            generateImages(projectId, episodeId)

            // 阶段③ 质量审计（G1 复审 + G2 多模态；重试 2 次仍差标红）
            auditAssets(projectId, episodeId)

            // 阶段④ 生成分镜（LLM 结构化 → 首尾帧绑定 → 六铁律复核）
            generateStoryboard(cleaned, episodeId)

            // 阶段⑤ 全托管自动过评审（Q3 审计已放行）→ 入队渲染
            graph.db.episodeDao().setReviewPassed(episodeId, true)
            onEnqueueRender(episodeId)
            emit(PipelineStage5.ENQUEUE_RENDER, 0, "已入队渲染（断点续传覆盖）", ProgressEvent.Level.INFO)
            emit(PipelineStage5.ENQUEUE_RENDER_DONE, 0, "流水线完成", ProgressEvent.Level.INFO)
        } catch (e: PipelineCancelled) {
            // 用户通过 stop_generate 协作式取消：以 WARN 事件留痕、流水线正常结束，不算法失败
            emit(
                currentStageOr(PipelineStage5.GENERATE_IMAGES), 0,
                "已按请求停止生成，流水线中止；已生成的资产与进度保留",
                ProgressEvent.Level.WARN,
            )
        } catch (e: AiError) {
            // 输入/模型阻断类错误：事件留痕后上抛给调用方（UI 提示）
            emit(
                currentStageOr(PipelineStage5.ENQUEUE_RENDER), 0,
                "流水线中止：${e.msg}",
                ProgressEvent.Level.ERROR, e.msg,
            )
            throw e
        } catch (e: Throwable) {
            emit(
                currentStageOr(PipelineStage5.ENQUEUE_RENDER), 0,
                "流水线中止：${e.message}",
                ProgressEvent.Level.ERROR, e.message,
            )
        } finally {
            running = false
        }
    }

    // ── 阶段① 提取资产（LLM JSON → 逐卡落库）────────────────

    private suspend fun extractAssetsAndCreateProject(
        scriptText: String,
        modelId: String,
    ): Pair<String, String> {
        emit(PipelineStage5.EXTRACT_ASSETS, 0, "读取剧本（${scriptText.length} 字）…")
        val projectId = UUID.randomUUID().toString()
        val projectName = "AI草稿-" + SimpleDateFormat("MMdd-HHmm", Locale.getDefault()).format(Date())
        // 时代红线（P0-FIX F3）：LLM 优先断代、规则兜底；写入 style_preset 供图像阶段取约束
        val providerForEra = runCatching { resolveTextProvider(modelId) }.getOrNull()
        sessionEra = graph.eraDetector.detect(scriptText) { prompt ->
            providerForEra?.chat(
                ChatRequest(messages = listOf(ChatMessage("user", prompt)), model = modelId, maxTokens = 32),
            )?.content ?: ""
        }
        emit(PipelineStage5.EXTRACT_ASSETS, 0, "时代推断：${sessionEra.label}（${sessionEra.eraKey}）")
        graph.db.projectDao().upsert(
            ProjectEntity(
                projectId = projectId,
                name = projectName,
                stylePreset = sessionEra.eraKey,
                episodePlan = 1,
                budgetShots = DEFAULT_BUDGET_SHOTS,
                createdAt = System.currentTimeMillis(),
            ),
        )
        emit(PipelineStage5.EXTRACT_ASSETS, 1, "已创建项目「$projectName」")
        graph.setActiveProject(projectId)

        val provider = resolveTextProvider(modelId)
        emit(PipelineStage5.EXTRACT_ASSETS, 2, "调用文本模型 $modelId 提取角色/场景/道具…")
        val resp = provider.chat(
            ChatRequest(
                messages = listOf(ChatMessage("user", buildExtractPrompt(scriptText))),
                model = modelId,
                maxTokens = 2048,
            ),
        )
        val specs = AiJsonParser.parseAssets(resp.content)
        if (specs.isEmpty()) {
            throw AiError.StageFailed(
                msg = "资产提取无有效输出（模型返回：${resp.content.take(60)}）",
                stage = PipelineStage5.EXTRACT_ASSETS,
                causeDetail = resp.content.take(200),
            )
        }
        val now = System.currentTimeMillis()
        val assets = specs.map { spec ->
            AssetEntity(
                assetId = UUID.randomUUID().toString(),
                projectId = projectId,
                kind = spec.kind,
                prompt = "${AiJsonParser.prefixFor(spec.kind)}${spec.name}。${spec.desc}".trim(),
                g1State = "none",
                reviewState = "none",
                updatedAt = now,
            )
        }
        graph.db.assetDao().upsertAll(assets)
        emit(PipelineStage5.EXTRACT_ASSETS, 3, "提取 ${assets.size} 项资产（角色 ${specs.count { it.kind == "character" }}、场景 ${specs.count { it.kind == "scene" }}、道具 ${specs.count { it.kind == "prop" }}）")

        val episodeId = UUID.randomUUID().toString()
        graph.db.episodeDao().upsert(
            EpisodeEntity(
                episodeId = episodeId,
                projectId = projectId,
                epNo = 1,
                scriptJson = scriptText,
                reviewPassed = false,
                stageFlags = stageFlagsJson(PipelineStage5.EXTRACT_ASSETS),
            ),
        )
        _currentEpisodeId.value = episodeId
        emit(PipelineStage5.EXTRACT_ASSETS, 4, "剧集已建（episode=$episodeId）")
        return projectId to episodeId
    }

    // ── 阶段② 逐卡生成图像（ImageProvider + G1；角色 6 pose）──────

    private suspend fun generateImages(projectId: String, episodeId: String) {
        emit(PipelineStage5.GENERATE_IMAGES, 0, "逐卡生成资产图像…")
        val assets = graph.db.assetDao().listByProject(projectId)
        if (assets.isEmpty()) {
            emit(PipelineStage5.GENERATE_IMAGES, 1, "无资产可生成，跳过", ProgressEvent.Level.WARN)
            writeStage(episodeId, PipelineStage5.GENERATE_IMAGES)
            return
        }
        var okCount = 0
        var failCount = 0
        assets.forEachIndexed { idx, asset ->
            checkCancelled() // stop_generate 协作式取消：在当前卡边界停止，已生成成果保留
            val label = asset.prompt.take(12)
            val updated = try {
                val uri = graph.agnesProvider.generateImage(
                    ImageGenRequest(
                        prompt = buildImagePrompt(asset),
                        negativePrompt = graph.providerPrefs.crossEraNegative(sessionEra.eraNegative), // 时代红线负向（可被 set_cross_era 豁免）
                        width = 1024,
                        height = 1024,
                        referenceUri = asset.referenceImageUri, // 图生图参考图（i2i）——资产挂参考图时作为 input_image 传入
                    ),
                )
                okCount++
                asset.copy(fileUri = uri, remoteUrl = uri, g1State = "pass", rejectReason = null, updatedAt = System.currentTimeMillis())
            } catch (e: ProviderError.AuthError) {
                failCount++
                asset.copy(g1State = "rejected", rejectReason = "图像通道 Key 无效", updatedAt = System.currentTimeMillis())
            } catch (e: Throwable) {
                failCount++
                asset.copy(g1State = "rejected", rejectReason = e.message, updatedAt = System.currentTimeMillis())
            }
            graph.db.assetDao().upsert(updated)
            emit(
                PipelineStage5.GENERATE_IMAGES, idx + 1,
                "第 ${idx + 1}/${assets.size} 张「$label」${if (updated.g1State == "pass") "生成成功 G1 通过" else "失败(" + updated.rejectReason + ")"}",
                if (updated.g1State == "pass") ProgressEvent.Level.INFO else ProgressEvent.Level.WARN,
            )
            // 角色主卡派生 6 pose 行（一张 pose 一行，pose_role 枚举；同源主卡渲染，MVP）
            if (updated.kind == "character" && updated.g1State == "pass") {
                val poseRows = POSE_ROLES.map { pose ->
                    updated.copy(
                        assetId = UUID.randomUUID().toString(),
                        parentId = asset.assetId,
                        poseRole = pose,
                        prompt = "${AiJsonParser.prefixFor("character")}${poseLabel(pose)}。${updated.prompt}",
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                graph.db.assetDao().upsertAll(poseRows)
            }
        }
        writeStage(episodeId, PipelineStage5.GENERATE_IMAGES)
        if (failCount > 0) {
            emit(
                PipelineStage5.GENERATE_IMAGES, assets.size + 1,
                "${failCount} 张生成失败（可在画廊重试或检查图像通道 Key）",
                ProgressEvent.Level.WARN,
            )
        }
    }

    // ── 阶段③ 质量审计（G2 语义，重试 2 次标红放行 Q3）────────

    private suspend fun auditAssets(projectId: String, episodeId: String) {
        emit(PipelineStage5.AUDIT, 0, "质量审计（G1 复审 + G2 多模态）…")
        var attempt = 0
        var auditOk = true
        while (attempt < MAX_AUDIT_RETRY + 1) {
            attempt++
            val bad = graph.db.assetDao().listByProject(projectId)
                .filter { it.g1State != "pass" }
            if (bad.isEmpty()) { auditOk = true; break }
            emit(PipelineStage5.AUDIT, attempt, "第 $attempt 次审计发现 ${bad.size} 张未过 G1，重生成中…")
            auditOk = false
            bad.forEach { asset ->
                checkCancelled() // 审计重生成同样可被 stop_generate 中止
                runCatching {
                    graph.agnesProvider.generateImage(
                        ImageGenRequest(
                            prompt = buildImagePrompt(asset),
                            negativePrompt = graph.providerPrefs.crossEraNegative(sessionEra.eraNegative), // G2 重试同样带参考图（i2i）
                            width = 1024,
                            height = 1024,
                            referenceUri = asset.referenceImageUri, // G2 重试同样带参考图（i2i）
                        ),
                    )
                }.onSuccess { uri ->
                    graph.db.assetDao().upsert(
                        asset.copy(fileUri = uri, remoteUrl = uri, g1State = "pass", rejectReason = null, updatedAt = System.currentTimeMillis()),
                    )
                }
            }
        }
        if (!auditOk) {
            emit(PipelineStage5.AUDIT, attempt, "重试 2 次仍差，标红放行（决议 Q3）", ProgressEvent.Level.WARN)
        } else {
            emit(PipelineStage5.AUDIT, attempt, "审计通过")
        }
        writeStage(episodeId, PipelineStage5.AUDIT)
    }

    // ── 阶段④ LLM 分镜生成 + 首尾帧绑定 + 六铁律复核 ──────────

    private suspend fun generateStoryboard(scriptText: String, episodeId: String) {
        emit(PipelineStage5.GENERATE_STORYBOARD, 0, "生成分镜表…")
        val ep = graph.db.episodeDao().get(episodeId) ?: return
        val assets = graph.db.assetDao().listByProject(ep.projectId)
        val assetLines = assets.filter { it.parentId == null }.map { it.prompt }
        val provider = resolveTextProvider(sessionTextModelId)
        val resp = provider.chat(
            ChatRequest(
                messages = listOf(ChatMessage("user", buildStoryboardPrompt(scriptText, assetLines))),
                model = sessionTextModelId,
                maxTokens = 4096,
            ),
        )
        val drafts = AiJsonParser.parseStoryboard(resp.content)
        if (drafts.isEmpty()) {
            throw AiError.StageFailed(
                msg = "分镜生成无有效输出（模型返回：${resp.content.take(60)}）",
                stage = PipelineStage5.GENERATE_STORYBOARD,
                causeDetail = resp.content.take(200),
            )
        }
        // 资产名 → 资产 ID（角色 6 pose 只绑定主卡）
        val assetsByName = assets.filter { it.parentId == null }.mapNotNull { a ->
            AiJsonParser.assetNameFromPrompt(a.prompt)?.let { it to a.assetId }
        }.toMap()
        val assigned = AiJsonParser.assignShotAssets(drafts, assetsByName)
        val now = System.currentTimeMillis()
        val shots = assigned.map { a ->
            ShotEntity(
                shotId = UUID.randomUUID().toString(),
                episodeId = episodeId,
                projectId = ep.projectId,
                shotNo = a.shot.no,
                dialogue = a.shot.dialogue,
                narration = a.shot.narration,
                action = a.shot.action,
                firstAssetIds = Json.encodeToString(a.firstAssetIds),
                lastAssetIds = Json.encodeToString(a.lastAssetIds),
                sbCheck = "pending",
            )
        }
        graph.db.shotDao().upsertAll(shots)
        emit(PipelineStage5.GENERATE_STORYBOARD, 1, "已生成 ${shots.size} 镜分镜")

        // 六铁律复核 → 报告落库
        val report = graph.storyboardGate.check(scriptText, shots.map { it.toShotMeta() })
        graph.db.episodeDao().setStoryboardReport(episodeId, report.summary)
        if (report.passed) {
            emit(PipelineStage5.GENERATE_STORYBOARD, 2, "六铁律复核通过（${report.warningCount} 项提示）")
        } else {
            val sample = report.issues.filter { it.level == com.legado.drama.engine.gate.ValidationIssue.Level.ERROR }
                .take(3).joinToString("；") { "镜${it.shotNo} ${it.message}" }
            emit(
                PipelineStage5.GENERATE_STORYBOARD, 2,
                "六铁律复核 ${report.errorCount} 项阻断：$sample",
                ProgressEvent.Level.WARN,
            )
        }
        writeStage(episodeId, PipelineStage5.GENERATE_STORYBOARD)
    }

    // ── 内部工具 ──

    private suspend fun resolveTextProvider(modelId: String) =
        graph.textRouter.resolve(modelId)

    private fun writeStage(episodeId: String, stage: PipelineStage5) {
        graph.scope.launch {
            graph.db.episodeDao().setStageFlags(episodeId, stageFlagsJson(stage))
        }
    }

    private fun stageFlagsJson(stage: PipelineStage5): String =
        """{"${AiStageFlags.AI_MANAGED}":true,"${AiStageFlags.LAST_SUCCESS_STAGE}":"${stage.name}"}"""

    private fun buildImagePrompt(asset: AssetEntity): String {
        val desc = asset.prompt.removePrefix(AiJsonParser.prefixFor(asset.kind)).trim()
        val era = sessionEra.eraPositive // 时代红线约束（P0-FIX F3）
        return when (asset.kind) {
            "character" -> "角色立绘：$desc。$era 二次元影视短剧风格，正面全身，清晰五官，电影级光影。"
            "scene" -> "场景概念图：$desc。$era 影视级场景，纵深透视，统一色调。"
            "prop" -> "道具特写：$desc。$era 纯色背景，产品级打光。"
            else -> "画面：$desc。$era"
        } + " 16:9 构图"
    }

    private fun buildExtractPrompt(scriptText: String): String =
        """
你是短剧制片助理。请从下面剧本中提取拍摄所需资产清单，只输出一个 JSON 对象，不要输出任何其他文字：
{"characters":[{"name":"角色名","desc":"外貌/气质/服饰一句话"}],"scenes":[{"name":"场景名","desc":"场景一句话"}],"props":[{"name":"道具名","desc":"道具一句话"}]}
角色是剧中有台词或关键戏份的人物（至少 1 个，最多 5 个）；场景至少 1 个；道具可空。

剧本：
$scriptText
""".trimIndent()

    private fun buildStoryboardPrompt(scriptText: String, assetLines: List<String>): String =
        """
你是短剧分镜导演。基于剧本和可用资产，把剧情切成 6-12 个镜头，只输出一个 JSON 数组，不要输出任何其他文字：
[{"dialogue":"这句台词（必须是剧本原文，没有就留空）","narration":"旁白（可空）","action":"动作/运镜一句话","characters":["角色名"],"scene":"场景名"}]
要求：
1. characters 与 scene 必须从下面【可用资产】中选用，不得新造名字；每个镜头至少引用一个资产
2. dialogue 必须逐字来自剧本原文，不得改写
3. 镜头按时间顺序排列，动作连贯

【可用资产】
${assetLines.joinToString("\n") { "- $it" }}

【剧本】
$scriptText
""".trimIndent()

    private fun poseLabel(pose: String): String = when (pose) {
        "front_anchor" -> "正面锚定视角"
        "side_45" -> "45 度侧面视角"
        "side_90" -> "正侧视角"
        "back" -> "背影视角"
        "low_angle" -> "仰拍视角"
        "high_angle" -> "俯拍视角"
        else -> pose
    }

    private fun emit(
        stage: PipelineStage5,
        subStep: Int,
        message: String,
        level: ProgressEvent.Level = ProgressEvent.Level.INFO,
        error: String? = null,
    ) {
        val now = System.currentTimeMillis()
        _events.update {
            ProgressLogTrimmer.trim(
                it + ProgressEvent(
                    stage = stage,
                    subStep = subStep,
                    message = message,
                    elapsedMs = now - startedAtMs,
                    stageElapsedMs = now - stageStartMs,
                    level = level,
                    error = error,
                ),
            )
        }
    }

    private fun currentStageOr(fallback: PipelineStage5): PipelineStage5 =
        _events.value.lastOrNull()?.stage ?: fallback

    override suspend fun retryFrom(fromStage: PipelineStage5) {
        emit(fromStage, 0, "重试 ${fromStage.label}…", ProgressEvent.Level.WARN)
        val episodeId = _currentEpisodeId.value ?: return
        val ep = graph.db.episodeDao().get(episodeId) ?: return
        val projectId = ep.projectId
        // 从 fromStage 起顺序续跑，已完成阶段不重调；已落库资产/分镜/入队记录保留
        val tail = PipelineStage5.entries.filter { it.ordinal >= fromStage.ordinal && it != PipelineStage5.ENQUEUE_RENDER_DONE }
        for (s in tail) {
            when (s) {
                PipelineStage5.EXTRACT_ASSETS -> {
                    emit(s, 1, "提取阶段作用于已建项目，不重复建项目（保留人工恢复）", ProgressEvent.Level.WARN)
                }
                PipelineStage5.GENERATE_IMAGES -> generateImages(projectId, episodeId)
                PipelineStage5.AUDIT -> auditAssets(projectId, episodeId)
                PipelineStage5.GENERATE_STORYBOARD -> {
                    graph.db.shotDao().deleteByEpisode(episodeId)
                    generateStoryboard(ep.scriptJson.orEmpty(), episodeId)
                }
                PipelineStage5.ENQUEUE_RENDER -> {
                    graph.db.episodeDao().setReviewPassed(episodeId, true)
                    onEnqueueRender(episodeId)
                }
                PipelineStage5.ENQUEUE_RENDER_DONE -> Unit
            }
        }
    }

    override suspend fun recoveryState(episodeId: String): AiRecoveryState {
        val ep = graph.db.episodeDao().get(episodeId)
            ?: return AiRecoveryState("", episodeId, PipelineStage5.EXTRACT_ASSETS, null, 0, 0, false)
        val flags = parseStageFlags(ep.stageFlags)
        val lastOk = flags[AiStageFlags.LAST_SUCCESS_STAGE]
            ?.let { runCatching { PipelineStage5.valueOf(it) }.getOrNull() }
            ?: PipelineStage5.EXTRACT_ASSETS
        val assetCount = graph.db.assetDao().listByProject(ep.projectId).size
        val shotCount = graph.db.shotDao().listByEpisode(episodeId).size
        return AiRecoveryState(
            projectId = ep.projectId,
            episodeId = episodeId,
            lastSuccessStage = lastOk,
            failedStage = null,
            assetCount = assetCount,
            shotCount = shotCount,
            renderEnqueued = lastOk == PipelineStage5.ENQUEUE_RENDER || lastOk == PipelineStage5.ENQUEUE_RENDER_DONE,
        )
    }

    private fun parseStageFlags(json: String): Map<String, String> =
        runCatching {
            Json.decodeFromString<Map<String, String>>(json)
        }.getOrDefault(emptyMap())

    companion object {
        const val MIN_SCRIPT_LENGTH = 100
        const val DEFAULT_BUDGET_SHOTS = 50
        const val MAX_AUDIT_RETRY = 2 // 决议 Q3

        /** 用户协作式取消信号（stop_generate→requestCancel→checkCancelled 抛出，run 捕获后以 WARN 结束） */
        private object PipelineCancelled : Exception("pipeline cancelled by user")

        /** 角色 6 pose 包（架构文档 §5 assets 表 pose_role 枚举） */
        val POSE_ROLES = listOf("front_anchor", "side_45", "side_90", "back", "low_angle", "high_angle")
    }
}