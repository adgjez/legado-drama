package com.legado.drama.assistant

import com.legado.drama.AppGraph
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.engine.assemble.MovieAssembler
import com.legado.drama.engine.gate.StylePreset
import com.legado.drama.engine.gate.StylePresets
import com.legado.drama.engine.orchestrate.ActionEnvelope
import com.legado.drama.engine.orchestrate.ActionResult
import com.legado.drama.engine.orchestrate.ActionStatus
import com.legado.drama.engine.orchestrator.AiJsonParser
import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ImageGenRequest
import com.legado.drama.orchestration.toShotMeta
import com.legado.drama.ui.Page
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Android 层动作执行器：AiAgent/StreamingAssistant → AppGraph 真实能力。
 *
 * 承接引擎层解码出的 [ActionEnvelope]，映射为 App 端真实调用：
 * 项目 / 剧本 / 资产 / 审查 / 姿态包 / 时代豁免 / 分镜 / 渲染 / 成片 / 导航。
 * 执行结果带 idempotency 语义：成功才登记（由引擎层 ctx 回写），此层只负责"真实执行 + 回显"。
 *
 * 未接线动作 fail-closed 返回 FAILED，不伪造成功。
 */
class AppActionExecutor(
    private val graph: AppGraph,
    private val onNavigate: ((Page) -> Unit)? = null,
) {

    /**
     * 执行一条已解码信封：返回 ActionResult。
     * @param env 已通过 decodeEnvelope 校验的动作信封
     */
    suspend fun execute(env: ActionEnvelope): ActionResult = when (env.verb) {
        // 项目 / 剧本 / 一键流水线
        "new_project" -> newProject(env)
        "open_project" -> openProject(env)
        "set_script" -> setScript(env)
        "test_drama" -> testDrama(env)
        "run_pipeline" -> runPipeline(env)
        // 查询类
        "list_assets" -> listAssets(env)
        "model_status" -> modelStatus(env)
        "render_status" -> renderStatus(env)
        // 资产级：提取 / 生成 / 编辑 / 删除 / 改类 / 审查 / 姿态包
        "extract_assets" -> extractAssets(env)
        "generate" -> generateAsset(env)
        "edit_asset" -> editAsset(env)
        "remove_asset" -> removeAsset(env)
        "change_asset_kind" -> changeAssetKind(env)
        "review_pass" -> reviewPass(env)
        "review_all_pass" -> reviewAllPass(env)
        "build_pose_pack" -> buildPosePack(env)
        // 时代红线豁免
        "set_cross_era" -> setCrossEra(env)
        // 分镜 / 渲染 / 成片
        "gen_shots" -> genShots(env)
        "render" -> renderNow(env)
        "render_pause" -> renderPause(env)
        "render_resume" -> renderResume(env)
        "compose_film" -> composeFilm(env)
        // 页面导航
        "goto" -> gotoPage(env)
        else -> fail(env, "「${env.verb}」未接入端侧执行器（可用动作见引擎 KNOWN_ACTIONS 白名单，未接线部分 fail-closed）", "NOT_WIRED")
    }

    // ── 项目 ───────────────────────────────────────────────

    private suspend fun newProject(env: ActionEnvelope): ActionResult {
        val name = env.args["name"]?.trim().orEmpty()
        if (name.isBlank()) {
            return fail(env, "缺少项目名（name）", "INVALID_ARGUMENTS")
        }
        val projectId = UUID.randomUUID().toString()
        graph.db.projectDao().upsert(
            ProjectEntity(
                projectId = projectId,
                name = name,
                stylePreset = "cinema",
                episodePlan = 1,
                budgetShots = 50,
                createdAt = System.currentTimeMillis(),
            ),
        )
        graph.setActiveProject(projectId)
        return ok(env, "已创建项目「$name」", listOf(projectId))
    }

    private suspend fun openProject(env: ActionEnvelope): ActionResult {
        val id = env.args["id"]?.trim().orEmpty().ifBlank { env.projectId.orEmpty() }
        if (id.isBlank()) {
            return fail(env, "缺少项目 id", "INVALID_ARGUMENTS")
        }
        val project = graph.db.projectDao().get(id)
            ?: return fail(env, "项目不存在：$id", "PROJECT_NOT_FOUND")
        graph.setActiveProject(id)
        return ok(env, "已打开项目「${project.name}」", listOf(id))
    }

    // ── 剧本 ───────────────────────────────────────────────

    private suspend fun setScript(env: ActionEnvelope): ActionResult {
        val text = env.args["text"] ?: env.args["script"]
        if (text.isNullOrBlank()) {
            return fail(env, "缺少剧本正文（text）", "INVALID_ARGUMENTS")
        }
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        // 找该项目的第 1 集；没有则新建
        val episodes = graph.db.episodeDao().listByProject(projectId)
        val first = episodes.firstOrNull { it.epNo == 1 }
        val episode = if (first != null) {
            first.copy(scriptJson = text)
        } else {
            EpisodeEntity(
                episodeId = UUID.randomUUID().toString(),
                projectId = projectId,
                epNo = 1,
                scriptJson = text,
                reviewPassed = false,
                stageFlags = "{}",
            )
        }
        graph.db.episodeDao().upsert(episode)
        return ok(env, "剧本已保存到第1集（${text.length} 字）", listOf(projectId, episode.episodeId))
    }

    /** 测试短剧：建项目 + 写剧本 + 触发一键流水线（全自动五阶段） */
    private suspend fun testDrama(env: ActionEnvelope): ActionResult {
        val name = env.args["name"]?.trim().orEmpty().ifBlank { "测试短剧" }
        val script = env.args["script"] ?: env.args["text"]
        if (script.isNullOrBlank()) {
            return fail(env, "缺少剧本正文（script）", "INVALID_ARGUMENTS")
        }
        val projectId = UUID.randomUUID().toString()
        graph.db.projectDao().upsert(
            ProjectEntity(
                projectId = projectId,
                name = name,
                stylePreset = "cinema",
                episodePlan = 1,
                budgetShots = 50,
                createdAt = System.currentTimeMillis(),
            ),
        )
        graph.setActiveProject(projectId)
        val episodeId = UUID.randomUUID().toString()
        graph.db.episodeDao().upsert(
            EpisodeEntity(
                episodeId = episodeId,
                projectId = projectId,
                epNo = 1,
                scriptJson = script,
                reviewPassed = false,
                stageFlags = "{}",
            ),
        )
        val pipelineId = triggerPipeline(script)
        return ok(env, "已建测试项目「$name」并启动流水线（pipeline=$pipelineId）", listOf(projectId, episodeId))
    }

    private suspend fun runPipeline(env: ActionEnvelope): ActionResult {
        val script = (env.args["script"] ?: env.args["text"])?.takeIf { it.isNotBlank() }
            ?: currentEpisodeScript(env)
        if (script == null) {
            return fail(env, "缺少剧本：请提供 script 或先 set_script", "NO_SCRIPT")
        }
        val pipelineId = triggerPipeline(script)
        return ok(env, "流水线已启动（建项目→提取→生成→审计→分镜→渲染，pipeline=$pipelineId）", emptyList())
    }

    /** 从当前项目取第一集剧本；无上下文则 null */
    private suspend fun currentEpisodeScript(env: ActionEnvelope): String? {
        val projectId = env.projectId ?: return null
        return graph.db.episodeDao().listByProject(projectId).firstOrNull()?.scriptJson
    }

    /** 异步触发五阶段全托管流水线（不阻塞对话），返回一个短 pipeline 标识 */
    private fun triggerPipeline(script: String): String {
        val modelId = graph.textRouter.activeTextModelId().ifBlank { "deepseek-chat" }
        val tag = "pl-" + System.currentTimeMillis().toString().takeLast(6)
        graph.scope.launch {
            runCatching {
                graph.aiOrchestrator.run(scriptText = script, textModelId = modelId)
            }.onFailure { e ->
                android.util.Log.w("AppActionExecutor", "pipeline $tag failed: $e")
            }
        }
        return tag
    }

    // ── 查询类 ─────────────────────────────────────────────

    private suspend fun listAssets(env: ActionEnvelope): ActionResult {
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        val assets = graph.db.assetDao().listByProject(projectId)
        if (assets.isEmpty()) {
            return ok(env, "该项目暂无资产，可先 set_script 再 run_pipeline", emptyList())
        }
        val byKind = assets.groupingBy { it.kind }.eachCount()
        val summary = byKind.entries.joinToString("、") { "${it.key}×${it.value}" }
        val sample = assets.take(5).joinToString("、") { it.assetId.takeLast(8) }
        return ok(env, "共 ${assets.size} 项资产（$summary），示例：$sample", emptyList())
    }

    private suspend fun modelStatus(env: ActionEnvelope): ActionResult {
        val active = graph.textRouter.activeTextModelId()
        val registered = graph.textRouter.registeredTextModels()
        val imageReady = graph.hasImageKey()
        val videoReady = graph.hasVideoKey()
        val textStatus = registered.firstOrNull { it.modelId == active }
            ?.let { if (it.isVerified) "已验证" else "未验证" } ?: "未配置"
        return ok(
            env,
            "文本模型 $active（$textStatus，共 ${registered.size} 个）；图像通道${if (imageReady) "✓" else "✗"}；视频通道${if (videoReady) "✓" else "✗"}",
            emptyList(),
        )
    }

    private suspend fun renderStatus(env: ActionEnvelope): ActionResult {
        val episodeId = env.episodeId
            ?: return fail(env, "缺少剧集上下文（episodeId）", "NO_EPISODE_CONTEXT")
        val tasks = graph.db.renderTaskDao().listByEpisode(episodeId)
        if (tasks.isEmpty()) {
            return ok(env, "该集暂无渲染任务，可先 run_pipeline", emptyList())
        }
        val byState = tasks.groupingBy { it.state }.eachCount()
        return ok(env, "渲染任务 ${tasks.size} 个（${byState.entries.joinToString("、") { "${it.key}×${it.value}" }}）", emptyList())
    }

    // ── 资产级动作 ─────────────────────────────────────────

    /** extract_assets：文本通道提取资产卡并落库（不生成图） */
    private suspend fun extractAssets(env: ActionEnvelope): ActionResult {
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        graph.db.projectDao().get(projectId)
            ?: return fail(env, "项目不存在", "PROJECT_NOT_FOUND")
        val script = graph.db.episodeDao().listByProject(projectId).firstOrNull()?.scriptJson
        if (script.isNullOrBlank()) {
            return fail(env, "项目暂无剧本，请先 set_script", "NO_SCRIPT")
        }
        val modelId = graph.textRouter.activeTextModelId().ifBlank { "deepseek-chat" }
        val provider = runCatching { graph.textRouter.resolve(modelId) }
            .getOrElse { return fail(env, "文本模型解析失败：${it.message}", "MODEL_RESOLVE_FAILED") }
        val resp = runCatching {
            provider.chat(
                ChatRequest(
                    messages = listOf(ChatMessage("user", extractPrompt(script))),
                    model = modelId,
                    maxTokens = 2048,
                ),
            )
        }.getOrElse { return fail(env, "资产提取调用失败：${it.message}", "EXTRACT_FAILED") }
        val specs = AiJsonParser.parseAssets(resp.content)
        if (specs.isEmpty()) {
            return fail(env, "资产提取无有效输出（模型返回：${resp.content.take(60)}）", "PARSE_EMPTY")
        }
        val now = System.currentTimeMillis()
        val assets = specs.map { spec ->
            AssetEntity(
                assetId = UUID.randomUUID().toString(),
                projectId = projectId,
                kind = spec.kind,
                prompt = "${AiJsonParser.prefixFor(spec.kind)}${spec.name}。${spec.desc}",
                g1State = "none",
                reviewState = "none",
                updatedAt = now,
            )
        }
        graph.db.assetDao().upsertAll(assets)
        val byKind = assets.groupingBy { it.kind }.eachCount()
        return ok(
            env,
            "提取 ${assets.size} 项资产（${byKind.entries.joinToString("、") { "${it.key}×${it.value}" }}），可 generate 逐卡生成图像",
            assets.map { it.assetId },
        )
    }

    /** generate：重生成单个资产图像（项目时代预设约束；失败置 rejected 不伪造成功） */
    private suspend fun generateAsset(env: ActionEnvelope): ActionResult {
        val assetId = env.args["assetId"]?.trim().orEmpty()
        if (assetId.isBlank()) {
            return fail(env, "缺少资产 id（assetId）", "INVALID_ARGUMENTS")
        }
        val asset = graph.db.assetDao().get(assetId)
            ?: return fail(env, "资产不存在：$assetId", "ASSET_NOT_FOUND")
        val project = graph.db.projectDao().get(asset.projectId)
        val era = project?.stylePreset?.let { StylePresets.presetFor(it) } ?: StylePresets.MODERN
        if (!graph.hasImageKey()) {
            return fail(env, "图像通道未配置 Key（设置页配置后重试）", "NO_IMAGE_KEY")
        }
        return try {
            val uri = graph.agnesProvider.generateImage(
                ImageGenRequest(
                    prompt = buildImagePromptFor(asset, era),
                    negativePrompt = graph.providerPrefs.crossEraNegative(era.eraNegative),
                    width = 1024,
                    height = 1024,
                    referenceUri = asset.referenceImageUri,
                ),
            )
            graph.db.assetDao().upsert(
                asset.copy(
                    fileUri = uri,
                    remoteUrl = uri,
                    g1State = "pass",
                    rejectReason = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            ok(env, "已生成「${asset.prompt.take(14)}」", listOf(uri))
        } catch (e: Throwable) {
            graph.db.assetDao().upsert(
                asset.copy(g1State = "rejected", rejectReason = e.message, updatedAt = System.currentTimeMillis()),
            )
            fail(env, "图像生成失败：${e.message}", "GENERATE_FAILED")
        }
    }

    /** edit_asset：直接改写资产 prompt（保留关心字段，触发重新生成） */
    private suspend fun editAsset(env: ActionEnvelope): ActionResult {
        val assetId = env.args["assetId"]?.trim().orEmpty()
        val prompt = env.args["prompt"]?.trim().orEmpty()
        if (assetId.isBlank() || prompt.isBlank()) {
            return fail(env, "缺少 assetId 或 prompt", "INVALID_ARGUMENTS")
        }
        val asset = graph.db.assetDao().get(assetId)
            ?: return fail(env, "资产不存在：$assetId", "ASSET_NOT_FOUND")
        graph.db.assetDao().upsert(
            asset.copy(prompt = prompt, g1State = "none", updatedAt = System.currentTimeMillis()),
        )
        return ok(env, "已更新资产描述，可 generate 重新生成", listOf(assetId))
    }

    /** remove_asset：删除资产及其派生卡（角色 pose 行随主卡删除） */
    private suspend fun removeAsset(env: ActionEnvelope): ActionResult {
        val assetId = env.args["assetId"]?.trim().orEmpty()
        if (assetId.isBlank()) {
            return fail(env, "缺少资产 id（assetId）", "INVALID_ARGUMENTS")
        }
        graph.db.assetDao().get(assetId)
            ?: return fail(env, "资产不存在：$assetId", "ASSET_NOT_FOUND")
        graph.db.assetDao().deleteWithDerived(assetId)
        return ok(env, "已删除资产及其派生卡", listOf(assetId))
    }

    /** change_asset_kind：角色/场景/道具互转（重写 prompt 前缀，保留原文描述） */
    private suspend fun changeAssetKind(env: ActionEnvelope): ActionResult {
        val assetId = env.args["assetId"]?.trim().orEmpty()
        val kind = env.args["kind"]?.trim().orEmpty()
        if (assetId.isBlank() || kind !in VALID_KINDS) {
            return fail(env, "缺少 assetId 或 kind（character/scene/prop）", "INVALID_ARGUMENTS")
        }
        val asset = graph.db.assetDao().get(assetId)
            ?: return fail(env, "资产不存在：$assetId", "ASSET_NOT_FOUND")
        val body = asset.prompt.removePrefix(AiJsonParser.prefixFor(asset.kind)).trim()
        graph.db.assetDao().upsert(
            asset.copy(
                kind = kind,
                prompt = "${AiJsonParser.prefixFor(kind)}$body",
                g1State = "none",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return ok(env, "资产已改为 $kind，可直接 generate", listOf(assetId))
    }

    /** review_pass：单资产标记保留（keep）；本集全部过审时联动置 reviewPassed */
    private suspend fun reviewPass(env: ActionEnvelope): ActionResult {
        val assetId = env.args["assetId"]?.trim().orEmpty()
        if (assetId.isBlank()) {
            return fail(env, "缺少资产 id（assetId）", "INVALID_ARGUMENTS")
        }
        val asset = graph.db.assetDao().get(assetId)
            ?: return fail(env, "资产不存在：$assetId", "ASSET_NOT_FOUND")
        graph.db.assetDao().updateReviewState(listOf(assetId), "keep")
        markReviewIfAllPassed(asset.projectId)
        return ok(env, "「${asset.prompt.take(14)}」已标记保留（keep）", listOf(assetId))
    }

    /** review_all_pass：项目全部资产标记保留并置本集审查通过（渲染放行前置） */
    private suspend fun reviewAllPass(env: ActionEnvelope): ActionResult {
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        val assets = graph.db.assetDao().listByProject(projectId)
        if (assets.isEmpty()) {
            return fail(env, "项目暂无资产，请先 extract_assets 或 run_pipeline", "NO_ASSETS")
        }
        graph.db.assetDao().updateReviewState(assets.map { it.assetId }, "keep")
        graph.db.episodeDao().listByProject(projectId).firstOrNull()?.let {
            graph.db.episodeDao().setReviewPassed(it.episodeId, true)
        }
        return ok(env, "全部 ${assets.size} 项资产已标记保留，本集审查通过", listOf(projectId))
    }

    /** build_pose_pack：为角色主卡派生 6 个姿态行（同源主卡渲染，MVP 对齐流水线） */
    private suspend fun buildPosePack(env: ActionEnvelope): ActionResult {
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        val characterId = env.args["characterId"]?.trim().orEmpty()
        val character = characterId.takeIf { it.isNotBlank() }
            ?.let { graph.db.assetDao().get(it) }
            ?: graph.db.assetDao().listByProject(projectId).firstOrNull {
                it.kind == "character" && it.parentId == null && it.g1State == "pass"
            }
            ?: return fail(env, "未找到可派生的角色主卡（需 kind=character 且图像已生成）", "NO_CHARACTER")
        val now = System.currentTimeMillis()
        val poseRows = POSE_ROLES.map { pose ->
            character.copy(
                assetId = UUID.randomUUID().toString(),
                parentId = character.assetId,
                poseRole = pose,
                prompt = "${AiJsonParser.prefixFor("character")}${poseLabel(pose)}。${character.prompt}",
                updatedAt = now,
            )
        }
        graph.db.assetDao().upsertAll(poseRows)
        return ok(
            env,
            "已为「${character.prompt.take(10)}」生成 ${poseRows.size} 个姿态卡（${POSE_ROLES.joinToString("、")}）",
            poseRows.map { it.assetId },
        )
    }

    /** set_cross_era：配置跨时代器物豁免（持久化；是图像负向词剔除的真实生效点） */
    private suspend fun setCrossEra(env: ActionEnvelope): ActionResult {
        val items = (env.args["items"] ?: env.args["allowed"])?.trim().orEmpty()
        if (items.isBlank()) {
            return fail(env, "缺少豁免器物（items，逗号分隔，如 手机,眼镜）", "INVALID_ARGUMENTS")
        }
        graph.providerPrefs.crossEraAllowed = items
        return ok(env, "跨时代豁免已生效：$items", emptyList())
    }

    // ── 分镜 / 渲染 / 成片 ─────────────────────────────────

    /** gen_shots：文本通道生成分镜 + 首尾帧绑定 + 六铁律复核落库 */
    private suspend fun genShots(env: ActionEnvelope): ActionResult {
        val projectId = env.projectId
            ?: return fail(env, "缺少项目上下文（projectId）", "NO_PROJECT_CONTEXT")
        val episode = graph.db.episodeDao().listByProject(projectId).firstOrNull()
            ?: return fail(env, "项目暂无剧集，请先 set_script", "NO_EPISODE")
        val script = episode.scriptJson
            ?: return fail(env, "项目暂无剧本", "NO_SCRIPT")
        val assets = graph.db.assetDao().listByProject(projectId)
        if (assets.isEmpty()) {
            return fail(env, "项目暂无资产，请先 extract_assets 或 run_pipeline", "NO_ASSETS")
        }
        val assetLines = assets.filter { it.parentId == null }.map { it.prompt }
        val modelId = graph.textRouter.activeTextModelId().ifBlank { "deepseek-chat" }
        val provider = runCatching { graph.textRouter.resolve(modelId) }
            .getOrElse { return fail(env, "文本模型解析失败：${it.message}", "MODEL_RESOLVE_FAILED") }
        val resp = runCatching {
            provider.chat(
                ChatRequest(
                    messages = listOf(ChatMessage("user", storyboardPrompt(script, assetLines))),
                    model = modelId,
                    maxTokens = 4096,
                ),
            )
        }.getOrElse { return fail(env, "分镜生成调用失败：${it.message}", "GEN_SHOTS_FAILED") }
        val drafts = AiJsonParser.parseStoryboard(resp.content)
        if (drafts.isEmpty()) {
            return fail(env, "分镜生成无有效输出（模型返回：${resp.content.take(60)}）", "PARSE_EMPTY")
        }
        val assetsByName = assets.filter { it.parentId == null }.mapNotNull { a ->
            AiJsonParser.assetNameFromPrompt(a.prompt)?.let { it to a.assetId }
        }.toMap()
        val assigned = AiJsonParser.assignShotAssets(drafts, assetsByName)
        val now = System.currentTimeMillis()
        val shots = assigned.map { a ->
            ShotEntity(
                shotId = UUID.randomUUID().toString(),
                episodeId = episode.episodeId,
                projectId = projectId,
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
        val report = graph.storyboardGate.check(script, shots.map { it.toShotMeta() })
        graph.db.episodeDao().setStoryboardReport(episode.episodeId, report.summary)
        return ok(env, "已生成 ${shots.size} 镜分镜；${report.summary}", listOf(episode.episodeId))
    }

    /** render：把整集渲染任务入队（后台逐镜执行，观察队列页） */
    private suspend fun renderNow(env: ActionEnvelope): ActionResult {
        val episodeId = env.episodeId ?: env.args["episodeId"]?.trim().orEmpty()
        if (episodeId.isBlank()) {
            return fail(env, "缺少剧集 id（episodeId）", "INVALID_ARGUMENTS")
        }
        val queueState = graph.renderQueue.state.value
        if (!queueState.pausedReason.isNullOrBlank()) {
            return fail(env, "渲染队列当前处于暂停（${queueState.pausedReason}），请先 render_resume", "QUEUE_PAUSED")
        }
        runCatching { graph.renderQueue.enqueueEpisode(episodeId) }
            .onFailure { return fail(env, "渲染入队失败：${it.message}", "ENQUEUE_FAILED") }
        return ok(env, "渲染已入队（${episodeId.takeLast(8)}），等待闹钟逐镜执行", listOf(episodeId))
    }

    /** render_pause：暂停渲染队列 */
    private suspend fun renderPause(env: ActionEnvelope): ActionResult {
        val reason = env.args["reason"]?.trim().orEmpty().ifBlank { "user" }
        runCatching { graph.renderQueue.pause(reason) }
            .onFailure { return fail(env, "暂停失败：${it.message}", "PAUSE_FAILED") }
        return ok(env, "渲染已暂停（$reason）", emptyList())
    }

    /** render_resume：恢复渲染（预算超限需用户确认 → confirmedByUser=true） */
    private suspend fun renderResume(env: ActionEnvelope): ActionResult {
        runCatching { graph.renderQueue.resume(confirmedByUser = true) }
            .onFailure { return fail(env, "恢复失败：${it.message}", "RESUME_FAILED") }
        return ok(env, "渲染已恢复", emptyList())
    }

    /** compose_film：取本集已完成单镜片段合成为成片并落库 finished_films */
    private suspend fun composeFilm(env: ActionEnvelope): ActionResult {
        val episodeId = env.episodeId ?: env.args["episodeId"]?.trim().orEmpty()
        if (episodeId.isBlank()) {
            return fail(env, "缺少剧集 id（episodeId）", "INVALID_ARGUMENTS")
        }
        val episode = graph.db.episodeDao().get(episodeId)
            ?: return fail(env, "剧集不存在：$episodeId", "EPISODE_NOT_FOUND")
        val tasks = graph.db.renderTaskDao().listByEpisode(episodeId)
        val clips = tasks
            .filter { it.state == "COMPLETED" && !it.localFileUri.isNullOrBlank() }
            .mapNotNull { File(it.localFileUri!!).takeIf { f -> f.exists() && f.length() > 0 } }
        if (clips.isEmpty()) {
            return fail(env, "无已完成的单镜片段（需先 render），无法合成", "NO_COMPLETED_CLIPS")
        }
        val output = File(graph.appContext.filesDir, "movies/$episodeId.mp4").apply { parentFile?.mkdirs() }
        val result = runCatching {
            graph.movieAssembler.assemble(clips, output, MovieAssembler.ColorGradePreset.CINEMA)
        }.getOrElse { return fail(env, "成片合成异常：${it.message}", "ASSEMBLE_FAILED") }
        return when (result) {
            is MovieAssembler.AssembleResult.Success -> {
                graph.db.finishedFilmDao().upsert(
                    FinishedFilmEntity(
                        filmId = episodeId,
                        episodeId = episodeId,
                        projectId = episode.projectId,
                        fileUri = result.output.absolutePath,
                        fileSize = result.output.length(),
                        durationSeconds = result.durationSeconds,
                        strategy = result.strategy.name,
                        partsUrisJson = "[]",
                        colorGrade = "CINEMA",
                        assembledAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                ok(env, "成片合成完成（${result.strategy.label}，${result.durationSeconds}s）", listOf(result.output.absolutePath))
            }
            is MovieAssembler.AssembleResult.Segmented -> {
                ok(env, "分段导出完成（${result.parts.size} 段，每段一个 mp4）", result.parts.map { it.absolutePath })
            }
            is MovieAssembler.AssembleResult.Failure -> {
                fail(env, "成片合成失败：${result.message}", "ASSEMBLE_FAILED")
            }
        }
    }

    // ── 导航 ───────────────────────────────────────────────

    /** goto：请求切到指定页面（未接线回调时 fail-closed） */
    private suspend fun gotoPage(env: ActionEnvelope): ActionResult {
        val page = env.args["page"]?.trim()?.lowercase().orEmpty()
        val target = when (page) {
            "projects" -> Page.PROJECTS
            "episodes" -> Page.EPISODES
            "assets" -> Page.ASSETS
            "storyboard" -> Page.STORYBOARD
            "queue" -> Page.QUEUE
            "library" -> Page.LIBRARY
            "settings" -> Page.SETTINGS
            else -> return fail(
                env,
                "未知页面：$page（可用 projects/episodes/assets/storyboard/queue/library/settings）",
                "INVALID_ARGUMENTS",
            )
        }
        val navigator = onNavigate ?: return fail(env, "导航回调未接线", "NOT_WIRED")
        navigator(target)
        return ok(env, "已跳转 ${target.name} 页", emptyList())
    }

    // ── 内部工具 ───────────────────────────────────────────

    /** 本集（项目第一集）全部资产均 keep 时，置审查通过（渲染放行前置） */
    private suspend fun markReviewIfAllPassed(projectId: String) {
        val total = graph.db.assetDao().countAll(projectId)
        val reviewed = graph.db.assetDao().countReviewed(projectId)
        if (total > 0 && reviewed >= total) {
            graph.db.episodeDao().listByProject(projectId).firstOrNull()?.let {
                graph.db.episodeDao().setReviewPassed(it.episodeId, true)
            }
        }
    }

    private fun extractPrompt(scriptText: String): String =
        """
你是短剧制片助理。请从下面剧本中提取拍摄所需资产清单，只输出一个 JSON 对象，不要输出任何其他文字：
{"characters":[{"name":"角色名","desc":"外貌/气质/服饰一句话"}],"scenes":[{"name":"场景名","desc":"场景一句话"}],"props":[{"name":"道具名","desc":"道具一句话"}]}
角色是剧中有台词或关键戏份的人物（至少 1 个，最多 5 个）；场景至少 1 个；道具可空。

剧本：
$scriptText
""".trimIndent()

    private fun storyboardPrompt(scriptText: String, assetLines: List<String>): String =
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

    /** 资产图像 prompt（对齐流水线时代约束语义，负向词交给调用方做跨时代豁免） */
    private fun buildImagePromptFor(asset: AssetEntity, era: StylePreset): String {
        val desc = asset.prompt.removePrefix(AiJsonParser.prefixFor(asset.kind)).trim()
        val eraText = era.eraPositive
        return when (asset.kind) {
            "character" -> "角色立绘：$desc。$eraText 二次元影视短剧风格，正面全身，清晰五官，电影级光影。"
            "scene" -> "场景概念图：$desc。$eraText 影视级场景，纵深透视，统一色调。"
            "prop" -> "道具特写：$desc。$eraText 纯色背景，产品级打光。"
            else -> "画面：$desc。$eraText"
        } + " 16:9 构图"
    }

    private fun poseLabel(pose: String): String = when (pose) {
        "front_anchor" -> "正面锚定视角"
        "side_45" -> "45 度侧面视角"
        "side_90" -> "正侧视角"
        "back" -> "背影视角"
        "low_angle" -> "仰拍视角"
        "high_angle" -> "俯拍视角"
        else -> pose
    }

    private fun ok(env: ActionEnvelope, message: String, payload: List<String>) =
        ActionResult(env.actionId, ActionStatus.SUCCEEDED, message, payload)

    private fun fail(env: ActionEnvelope, message: String, code: String) =
        ActionResult(env.actionId, ActionStatus.FAILED, message, emptyList(), code)

    private companion object {
        val POSE_ROLES = listOf("front_anchor", "side_45", "side_90", "back", "low_angle", "high_angle")
        val VALID_KINDS = setOf("character", "scene", "prop")
    }
}