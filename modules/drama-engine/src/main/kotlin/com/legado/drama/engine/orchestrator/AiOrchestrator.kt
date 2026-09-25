package com.legado.drama.engine.orchestrator

import kotlinx.coroutines.flow.StateFlow

/**
 * T014 AI 全托管五阶段编排（T014-arch.md §2.2）：
 * ProgressEvent 进度日志流 + PipelineStage5 五阶段 + AiOrchestrator 接口 + 恢复状态。
 */

/** 流式进度事件（P0-2 验收第 2 条） */
data class ProgressEvent(
    /** 五阶段枚举，对应 AI 全托管流水线顺序 */
    val stage: PipelineStage5,
    /** 阶段内子步骤序号（如 extractAssets 阶段可拆"分镜文本→角色→场景→道具"） */
    val subStep: Int,
    /** 用户可读中文消息，日志流直接展示 */
    val message: String,
    /** 毫秒时间戳（相对流水线起点） */
    val elapsedMs: Long,
    /** 阶段耗时毫秒（阶段首次到当前事件） */
    val stageElapsedMs: Long,
    /** 事件等级：INFO / WARN / ERROR */
    val level: Level = Level.INFO,
    /** 失败原因（仅 ERROR 时非空） */
    val error: String? = null,
) {
    enum class Level { INFO, WARN, ERROR }

    /** 该事件是否表示整条流水线结束（含失败） */
    val isTerminal: Boolean
        get() = stage == PipelineStage5.ENQUEUE_RENDER && level == Level.ERROR ||
            stage == PipelineStage5.ENQUEUE_RENDER_DONE
}

/** 五阶段枚举：与 PRD P0-2 验收第 1 条顺序一致 */
enum class PipelineStage5(val label: String) {
    EXTRACT_ASSETS("①提取资产"),
    GENERATE_IMAGES("②生成图像"),
    AUDIT("③质量审计"),
    GENERATE_STORYBOARD("④生成分镜"),
    ENQUEUE_RENDER("⑤入队渲染"),
    ENQUEUE_RENDER_DONE("✓ 完成"),
}

/**
 * AI 全托管五阶段编排器（core-engine 新增，JVM 可测）。
 * 内部契约（开发 Agent 必须满足，供单测断言）：
 * 1. run 内部严格按 PipelineStage5 顺序执行，任一阶段抛异常 → 立即追加 ERROR 事件
 *    → 停止 → 已建项目/集/资产/分镜保留。
 * 2. 自动建项目命名规则："AI草稿-" + SimpleDateFormat("MMdd-HHmm")（本地时区），
 *    stage_flags 写入 {"ai_managed":true} 以便 UI 识别。
 * 3. audit 复用 QualityEngine，重试上限 = 2，仍差则标红事件 + 继续。
 * 4. 文本通道路由：textModelId 非空时用 TextModelRouter.resolve(textModelId)；
 *    空时走 AppGraph.text 默认。
 */
interface AiOrchestrator {

    /** 五阶段流水线实时进度 */
    val events: StateFlow<List<ProgressEvent>>

    /** 当前编排运行中的 episodeId（自动建项目后填入）；未启动为 null */
    val currentEpisodeId: StateFlow<String?>

    /**
     * 一键成片主入口。
     * @param scriptText 用户粘贴的剧本文本，长度 ≥100 字符
     * @param textModelId 文本通道模型 id（如 "deepseek-chat" / "agnes-2.5-flash"）；空串则使用默认
     * @param onAutoCreatedProject 自动建项目后回调（UI 用其切到剧集/分镜/渲染页）
     */
    suspend fun run(
        scriptText: String,
        textModelId: String = "",
        onAutoCreatedProject: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
    )

    /** 重试某一阶段：已完成阶段结果不重复调用，仅从 fromStage 起重新跑 */
    suspend fun retryFrom(fromStage: PipelineStage5)

    /** 五阶段进度（checkpoint）：已落 Room，读回即可恢复 */
    suspend fun recoveryState(episodeId: String): AiRecoveryState

    sealed class AiError(open val msg: String) : Exception(msg) {
        class InputTooShort(msg: String) : AiError(msg)
        class ModelBlocked(
            msg: String,
            val modelId: String,
        ) : AiError(msg)
        class StageFailed(
            msg: String,
            val stage: PipelineStage5,
            val causeDetail: String,
        ) : AiError(msg)
    }

    /** 恢复状态：哪一阶段已成功、哪一阶段待重试 */
    data class AiRecoveryState(
        val projectId: String,
        val episodeId: String,
        val lastSuccessStage: PipelineStage5,  // 最后一阶段成功
        val failedStage: PipelineStage5?,       // 失败阶段（可为空表示全部成功或从未启动）
        val assetCount: Int,
        val shotCount: Int,
        val renderEnqueued: Boolean,
    )
}

/** 进度日志流裁剪（T014 R9）：默认上限 500 条，保留末 200 条 */
object ProgressLogTrimmer {
    const val DEFAULT_MAX = 500
    const val KEEP_LAST = 200

    fun trim(events: List<ProgressEvent>, max: Int = DEFAULT_MAX): List<ProgressEvent> {
        if (events.size <= max) return events
        return events.takeLast(KEEP_LAST)
    }
}