package com.legado.drama.assistant

import com.legado.drama.AppGraph
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.engine.orchestrate.ActionResult
import com.legado.drama.engine.orchestrate.ActionStatus
import com.legado.drama.engine.orchestrate.ActionEnvelope
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Android 层动作执行器（T4：AiAgent/StreamingAssistant → AppGraph 真实能力）。
 *
 * 承接引擎层解码出的 [ActionEnvelope]，映射为 App 端真实调用：
 * 建项目 / 打开项目 / 写剧本 / 一键流水线 / 查模型 / 渲染进度等。
 * 执行结果带 idempotency 语义：成功才登记（由引擎层 ctx 回写），此层只负责“真实执行 + 回显”。
 *
 * 未接线动作 fail-closed 返回 FAILED，不伪造成功。
 */
class AppActionExecutor(private val graph: AppGraph) {

    /**
     * 执行一条已解码信封：返回 ActionResult。
     * @param env 已通过 decodeEnvelope 校验的动作信封
     */
    suspend fun execute(env: ActionEnvelope): ActionResult = when (env.verb) {
        "new_project" -> newProject(env)
        "open_project" -> openProject(env)
        "set_script" -> setScript(env)
        "test_drama" -> testDrama(env)
        "list_assets" -> listAssets(env)
        "run_pipeline" -> runPipeline(env)
        "model_status" -> modelStatus(env)
        "render_status" -> renderStatus(env)
        else -> ActionResult(
            env.actionId,
            ActionStatus.FAILED,
            "「${env.verb}」未接入端侧执行器（可用 new_project / set_script / run_pipeline）",
            emptyList(),
            "NOT_WIRED",
        )
    }

    // ── 项目 ───────────────────────────────────────────────

    private suspend fun newProject(env: ActionEnvelope): ActionResult {
        val name = env.args["name"]?.trim().orEmpty()
        if (name.isBlank()) {
            return ActionResult(env.actionId, ActionStatus.FAILED, "缺少项目名（name）", emptyList(), "INVALID_ARGUMENTS")
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
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "已创建项目「$name」",
            listOf(projectId),
        )
    }

    private suspend fun openProject(env: ActionEnvelope): ActionResult {
        val id = env.args["id"]?.trim().orEmpty().ifBlank { env.projectId.orEmpty() }
        if (id.isBlank()) {
            return ActionResult(env.actionId, ActionStatus.FAILED, "缺少项目 id", emptyList(), "INVALID_ARGUMENTS")
        }
        val project = graph.db.projectDao().get(id)
            ?: return ActionResult(env.actionId, ActionStatus.FAILED, "项目不存在：$id", emptyList(), "PROJECT_NOT_FOUND")
        graph.setActiveProject(id)
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "已打开项目「${project.name}」",
            listOf(id),
        )
    }

    // ── 剧本 ───────────────────────────────────────────────

    private suspend fun setScript(env: ActionEnvelope): ActionResult {
        val text = env.args["text"] ?: env.args["script"]
        if (text.isNullOrBlank()) {
            return ActionResult(env.actionId, ActionStatus.FAILED, "缺少剧本正文（text）", emptyList(), "INVALID_ARGUMENTS")
        }
        val projectId = env.projectId ?: return ActionResult(
            env.actionId, ActionStatus.FAILED, "缺少项目上下文（projectId）", emptyList(), "NO_PROJECT_CONTEXT",
        )
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
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "剧本已保存到第1集（${text.length} 字）",
            listOf(projectId, episode.episodeId),
        )
    }

    /** 测试短剧：建项目 + 写剧本 + 触发一键流水线（全自动五阶段） */
    private suspend fun testDrama(env: ActionEnvelope): ActionResult {
        val name = env.args["name"]?.trim().orEmpty().ifBlank { "测试短剧" }
        val script = env.args["script"] ?: env.args["text"]
        if (script.isNullOrBlank()) {
            return ActionResult(env.actionId, ActionStatus.FAILED, "缺少剧本正文（script）", emptyList(), "INVALID_ARGUMENTS")
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
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "已建测试项目「$name」并启动流水线（pipeline=$pipelineId）",
            listOf(projectId, episodeId),
        )
    }

    private suspend fun runPipeline(env: ActionEnvelope): ActionResult {
        val script = (env.args["script"] ?: env.args["text"])?.takeIf { it.isNotBlank() }
            ?: currentEpisodeScript(env)
        if (script == null) {
            return ActionResult(env.actionId, ActionStatus.FAILED, "缺少剧本：请提供 script 或先 set_script", emptyList(), "NO_SCRIPT")
        }
        val pipelineId = triggerPipeline(script)
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "流水线已启动（建项目→提取→生成→审计→分镜→渲染，pipeline=$pipelineId）",
            emptyList(),
        )
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
            ?: return ActionResult(env.actionId, ActionStatus.FAILED, "缺少项目上下文（projectId）", emptyList(), "NO_PROJECT_CONTEXT")
        val assets = graph.db.assetDao().listByProject(projectId)
        if (assets.isEmpty()) {
            return ActionResult(env.actionId, ActionStatus.SUCCEEDED, "该项目暂无资产，可先 set_script 再 run_pipeline", emptyList())
        }
        val byKind = assets.groupingBy { it.kind }.eachCount()
        val summary = byKind.entries.joinToString("、") { "${it.key}×${it.value}" }
        val sample = assets.take(5).joinToString("、") { it.assetId.takeLast(8) }
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "共 ${assets.size} 项资产（$summary），示例：$sample",
            emptyList(),
        )
    }

    private suspend fun modelStatus(env: ActionEnvelope): ActionResult {
        val active = graph.textRouter.activeTextModelId()
        val registered = graph.textRouter.registeredTextModels()
        val imageReady = graph.hasImageKey()
        val videoReady = graph.hasVideoKey()
        val textStatus = registered.firstOrNull { it.modelId == active }
            ?.let { if (it.isVerified) "已验证" else "未验证" } ?: "未配置"
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "文本模型 $active（$textStatus，共 ${registered.size} 个）；图像通道${if (imageReady) "✓" else "✗"}；视频通道${if (videoReady) "✓" else "✗"}",
            emptyList(),
        )
    }

    private suspend fun renderStatus(env: ActionEnvelope): ActionResult {
        val episodeId = env.episodeId
            ?: return ActionResult(env.actionId, ActionStatus.FAILED, "缺少剧集上下文（episodeId）", emptyList(), "NO_EPISODE_CONTEXT")
        val tasks = graph.db.renderTaskDao().listByEpisode(episodeId)
        if (tasks.isEmpty()) {
            return ActionResult(env.actionId, ActionStatus.SUCCEEDED, "该集暂无渲染任务，可先 run_pipeline", emptyList())
        }
        val byState = tasks.groupingBy { it.state }.eachCount()
        return ActionResult(
            env.actionId,
            ActionStatus.SUCCEEDED,
            "渲染任务 ${tasks.size} 个（${byState.entries.joinToString("、") { "${it.key}×${it.value}" }}）",
            emptyList(),
        )
    }
}