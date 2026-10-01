package com.legado.drama.engine.orchestrate

import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.TextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * 流式、多轮 AI 助手（T014 语义移植）；旧 AiAgent.say 接口保持不变。
 *
 * 与 [AiAgent] 的非流式说一句话不同，[sayStreaming] 以 Flow<StreamChunk> 逐段送达：
 *   - TextDelta 自然语言增量（UI 直接渲染）
 *   - ActionComplete 结构化动作回显卡片
 *   - Done 携带完整回复（收尾/落库）
 *
 * P0 动作可靠性：流式整段文本先 [parseActions] 再逐条 [decodeEnvelope]（fail-closed），
 * 提供 [envelopeHandler] 时按结构化信封路由（含幂等预留/完成/释放），缺省回退旧 [actionHandler]。
 */
class StreamingAssistant(
    private val textProvider: TextProvider,
    private val modelId: String,
    /** 旧路径动作执行器（挂起）：返回一句话结果；返回 null 视为无法执行 */
    private val actionHandler: suspend (ActionIntent) -> String? = { null },
    /**
     * P0 动作可靠性：结构化信封处理器。提供时按 [ActionEnvelope] 路由（解码 + 幂等去重 +
     * fail-closed），执行层以 [ActionResult] 为准；缺省回退 [actionHandler] 兼容旧路径。
     */
    private val envelopeHandler: suspend (ActionEnvelope) -> ActionResult =
        { env -> ActionResult(env.actionId, ActionStatus.SUCCEEDED, "") },
    /**
     * 动作上下文（当前项目/集 + 幂等键去重集合），由 App 层在构建时注入。
     */
    private val actionContext: ActionContext = ActionContext(),
    /**
     * 会话上下文提供器（跨轮动态注入）：每轮 sayStreaming 开始时调用，返回最新项目/集上下文。
     * 非空时优先于静态 [actionContext]（如 App 端已有"当前项目"时，AI 跨轮 set_script
     * 自动复用该项目）。默认 null 保持静态行为。
     */
    private val contextFactory: (() -> ActionContext)? = null,
    private val idempotencyStore: ActionIdempotencyStore? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 消息滑动窗口保留的最大对话轮数（一轮 = user + assistant），默认 10 */
    private val maxTurns: Int = 10,
    /** LLM 采样温度，默认 0.7（创意写作适中） */
    private val temperature: Double = 0.7,
    /** 日志输出函数（纯 Kotlin 引擎不含 Android Log） */
    private val logger: (String) -> Unit = { println(it) },
) {
    private val messages = mutableListOf(ChatMessage(role = "system", content = SYSTEM_PROMPT))
    private val turns = mutableListOf<DialogueTurn>()
    val history: List<DialogueTurn> get() = turns.toList()

    /** 多轮流式一问一答：逐段发射 TextDelta → ActionComplete → Done */
    fun sayStreaming(userText: String): Flow<StreamChunk> = flow {
        val text = userText.trim()
        if (text.isBlank()) {
            emit(StreamChunk.Done(""))
            return@flow
        }
        turns += DialogueTurn(DialogueTurn.Side.USER, text, nowMs())
        messages += ChatMessage(role = "user", content = text)
        trimMessages()
        val raw = StringBuilder()
        try {
            textProvider.streamChat(
                ChatRequest(messages = messages.toList(), model = modelId, temperature = temperature),
            ).collect { part ->
                if (part.isNotEmpty()) {
                    raw.append(part)
                    emit(StreamChunk.TextDelta(part))
                }
            }
        } catch (t: Throwable) {
            // 技术异常转成人话，避免用户看到裸 TransientError 堆栈
            val msg = when (t) {
                is ProviderError.AuthError -> "API Key 无效或已过期，请去设置页检查文本模型 Key。"
                is ProviderError.QuotaError -> "API 调用配额已用完，请稍后再试。"
                is ProviderError.ValidationError -> "请求参数有误：${t.message?.take(80)}"
                else -> "AI 暂时没有响应，请稍后再试。"
            }
            logger("StreamingAssistant chat failed: ${t.message}")
            raw.append("⚠️ ").append(msg)
            emit(StreamChunk.TextDelta("⚠️ $msg"))
        }
        val full = raw.toString().ifBlank { "（AI 没有回复，请重试）" }
        val display = full.lines()
            .filterNot { it.trim().startsWith(ActionIntent.MARK) }
            .joinToString("\n").trimEnd()

        // P0：整段流式文本（已跨 chunk 缓冲）逐条解析为 [ACT]，解码为信封执行；缺省回退旧路径。
        // 跨轮上下文：优先使用 App 端动态提供的 contextFactory（如当前项目），否则静态 actionContext。
        val base = contextFactory?.invoke() ?: actionContext
        // 同一轮内动作互哺：new_project 执行成功回写 projectId / episodeId，
        // 后续 set_script / extract_assets 等 CONTEXT_REQUIRED 动作在同轮即可通过校验。
        var ctx = ActionContext(
            projectId = base.projectId,
            episodeId = base.episodeId,
            knownIdempotencyKeys = base.knownIdempotencyKeys,
        )
        val notes = mutableListOf<String>()
        var blocked = 0
        for (action in parseActions(full)) {
            val result: ActionResult
            when (val decoded = decodeEnvelope(action, ctx)) {
                is DecodedAction.Ok -> {
                    val key = decoded.envelope.idempotencyKey
                    if (key != null && idempotencyStore != null && !idempotencyStore.reserve(key)) {
                        result = ActionResult(
                            decoded.envelope.actionId, ActionStatus.BLOCKED,
                            "幂等键已处理过：$key，禁止重复执行", errorCode = "DUPLICATE_IDEMPOTENCY",
                        )
                        blocked++
                    } else {
                        result = try {
                            envelopeHandler(decoded.envelope)
                        } catch (t: Throwable) {
                            if (key != null) idempotencyStore?.release(key)
                            throw t
                        }
                        if (key != null && idempotencyStore != null) {
                            if (result.status == ActionStatus.SUCCEEDED) idempotencyStore.complete(key)
                            else idempotencyStore.release(key)
                        }
                        ctx.markSucceeded(decoded.envelope, result)
                        // 动作执行成功后，把结果中声明的实体引用（约定：entityRefs[0]=projectId,
                        // entityRefs[1]=episodeId）回写进本轮回话上下文，供后续动作复用
                        if (result.status == ActionStatus.SUCCEEDED) {
                            result.entityRefs.getOrNull(0)?.takeIf { ctx.projectId.isNullOrBlank() }
                                ?.let { p -> ctx = ctx.copy(projectId = p) }
                            result.entityRefs.getOrNull(1)?.takeIf { ctx.episodeId.isNullOrBlank() }
                                ?.let { e -> ctx = ctx.copy(episodeId = e) }
                        }
                    }
                }
                is DecodedAction.Rejected -> {
                    result = decoded.result
                    blocked++
                }
            }
            notes += result.message.ifBlank { action.verb }
            emit(StreamChunk.ActionComplete(action.verb, result.message))
        }
        val incomplete = if (blocked > 0) "；$blocked 项未完成" else ""
        val finalText = if (notes.isEmpty()) display
        else display + "\n（已为你执行：${notes.joinToString("；")}$incomplete）"

        turns += DialogueTurn(DialogueTurn.Side.AI, finalText, nowMs())
        // 上下文保留原始（含 [ACT]），便于多轮连贯
        messages += ChatMessage(role = "assistant", content = full)
        emit(StreamChunk.Done(finalText))
    }

    /** 滑动窗口裁剪：保留 system + 最近 maxTurns 轮完整对话对 */
    private fun trimMessages() {
        while (messages.size > 1 + maxTurns * 2) {
            val i = messages.indexOfFirst { it.role != "system" }
            if (i < 0) return
            messages.removeAt(i)
            if (i < messages.size && messages[i].role != "system") messages.removeAt(i)
        }
    }

    companion object {
        /** 系统人设：短剧操控助手 + 编剧导演搭档，教学全部可用动作与硬规则 */
        private const val SYSTEM_PROMPT = """你是「AI短剧工厂」手机App里的操控助手，也是用户的编剧导演搭档。请用自然、简洁、口语化的中文回应，不要机械复述或写客服式清单。你不只是聊天：用户明确要求操作时，必须真正调用App模块。

【机器指令协议】需要操作App时，在回复正文末尾另起一行输出一条或多条：
[ACT] <动作> | 参数=值 | 参数=值
机器指令不要放进正文，不要编造执行结果。

【可用动作】
- new_project：建项目，例 [ACT] new_project | name=雪夜镖局
- set_script：把剧本写入当前项目，例 [ACT] set_script | text=剧本文本
- open_project：打开项目，例 [ACT] open_project | id=p_xxx
- test_drama：创建并保存测试项目和剧本，例 [ACT] test_drama | name=测试短剧 | script=剧本文本
- list_assets / extract_assets / generate / stop_generate / edit_asset / remove_asset / change_asset_kind / review_pass / review_all_pass / build_pose_pack / set_cross_era
- gen_shots / render / render_status / render_pause / render_resume / compose_film / run_pipeline
- model_status / goto：切换页面，例 [ACT] goto | page=projects

【硬规则】
1. 用户说“建项目/创建项目/新建项目”时，必须输出 new_project，不得只口头答应。
2. 用户同时给出剧本时，先输出 new_project，再输出 set_script；不要假称已保存。
3. 用户说“测试短剧/试做/先跑个测试”时，必须输出 test_drama，它会创建并保存项目与第1集剧本后再跑流程。
4. 操作资产前先 list_assets，禁止编造 assetId。
5. 缺少必要信息时只追问缺的那一项；动作执行结果由App回显，不能自行杜撰。
6. 用户问“进度/到哪了/好了没”时先发 render_status 拿真实进度再回答。"""
    }
}