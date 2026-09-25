package com.legado.drama.engine.orchestrator

import kotlinx.coroutines.flow.StateFlow

/**
 * 管线编排器（架构文档 §3）：七阶段状态机 S1..S7 + Gate 判定与阶段推进。
 * Gate 位图：各关口放行的位掩码（评审全过/校验通过/Key 有效/预算未超）。
 */
enum class PipelineStage(val label: String) {
    S1_PROJECT("①项目"),
    S2_IMPORT("②导入"),
    S3_ASSETS("③资产"),
    S4_REVIEW("④评审"),
    S5_STORYBOARD("⑤分镜"),
    S6_RENDER("⑥渲染"),
    S7_FILM("⑦成片"),
}

/** 各 Gate 通过位图（evaluateGates 返回） */
data class GateReport(
    val projectId: String,
    val assetsGenerated: Boolean = false,  // 资产库非空且 G1 通过
    val reviewPassed: Boolean = false,      // S4 人工评审全过（F04 硬门槛）
    val storyboardPassed: Boolean = false,  // S5 六铁律 pass
    val keyValid: Boolean = false,          // 关键通道 Key 已验证
    val budgetOk: Boolean = false,          // 预算未超
    val canRender: Boolean = false,         // 全部前置满足
)

/**
 * 编排器：进程重启后的恢复总入口
 * 读 checkpoint → 先 repoll 已提交镜 → 续跑队列。
 */
interface PipelineOrchestrator {
    val stage: StateFlow<PipelineStage> // S1..S7 + 各 Gate 通过位图
    suspend fun evaluateGates(projectId: String): GateReport
    suspend fun advanceTo(stage: PipelineStage)
    suspend fun recoverOnBoot()
}

/** Gate 评估纯逻辑（可 JVM 单测）：汇总各闸门最终放行判定 */
object GateEvaluationLogic {

    fun compose(
        assetsGenerated: Boolean,
        reviewPassed: Boolean,
        storyboardPassed: Boolean,
        keyValid: Boolean,
        budgetOk: Boolean,
    ): GateReport {
        val r = GateReport(
            projectId = "",
            assetsGenerated = assetsGenerated,
            reviewPassed = reviewPassed,
            storyboardPassed = storyboardPassed,
            keyValid = keyValid,
            budgetOk = budgetOk,
        )
        return r.copy(canRender = r.assetsGenerated && r.reviewPassed && r.storyboardPassed && r.keyValid && r.budgetOk)
    }
}