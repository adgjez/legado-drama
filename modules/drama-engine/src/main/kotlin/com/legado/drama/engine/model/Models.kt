package com.legado.drama.engine.model

import kotlinx.serialization.Serializable

/**
 * 数据契约层（纯 Kotlin，JVM 可测）。
 * 与 Room 表的对应关系见 ai-drama-factory-architecture.md §5：
 * projects / assets / shots / render_tasks / provider_configs / episodes / finished_films。
 * 本层只描述跨层传递的数据形状，不承载任何 Android 依赖。
 */

/** ── projects ─────────────────────────────────────────────── */
@Serializable
data class ProjectMeta(
    val projectId: String,          // uuid
    val name: String,
    val stylePreset: String = "cinema",
    val episodePlan: Int = 1,
    val budgetShots: Int = 50,      // 条数型预算（Q2）
    val createdAt: Long,
)

/** ── assets（角色 6pose / 场景 / 道具，一张 pose 一行）──────── */
@Serializable
data class AssetMeta(
    val assetId: String,
    val projectId: String,
    val kind: String,               // character/scene/prop
    val parentId: String? = null,   // pose 行指向角色主卡
    val poseRole: String? = null,   // front_anchor/side_45/...
    val prompt: String,
    val fileUri: String? = null,
    val remoteUrl: String? = null,
    val g1State: String = "none",   // none/pass/rejected
    val g2Score: Double? = null,
    val g2Defects: String? = null,  // defects 非空直接拒（pavo 实战）
    val reviewState: String = "none", // none/keep/regen（人工评审 F04）
    val rejectReason: String? = null,
    val seed: Long? = null,
    val updatedAt: Long,
) {
    val isG1Passed: Boolean get() = g1State == "pass"
    val isG2Passed: Boolean get() = g2Score != null && g2Defects.isNullOrBlank()
    val isReviewed: Boolean get() = reviewState == "keep"
}

/** ── shots（每集每镜，跨层传递的统一形状）─────────────────── */
@Serializable
data class ShotMeta(
    val shotId: String,
    val episodeId: String = "",
    val projectId: String = "",
    val shotNo: Int = 0,
    val dialogue: String? = null,
    val narration: String? = null,
    val action: String? = null,
    val beatRef: String? = null,
    val carryOver: String? = null,
    val firstAssetIds: List<String> = emptyList(), // JSON 数组（绑定资产/道具母图）
    val lastAssetIds: List<String> = emptyList(),
    val sbCheck: String = "pending",  // 六铁律: pending/pass/error(JSON 详情)
)

/** ── episodes ──────────────────────────────────────────────── */
@Serializable
data class EpisodeMeta(
    val episodeId: String,
    val projectId: String,
    val epNo: Int,
    val scriptJson: String? = null,
    val storyboardReport: String? = null, // 六铁律报告原文
    val reviewPassed: Boolean = false,    // 资产评审全过才置 1（F04 硬门槛）
    val stageFlags: Map<String, String> = emptyMap(), // 各 Gate 通过位图 JSON
)

/** ── provider_configs（Key 密文存这里，明文只在内存解瞬间存在）─ */
@Serializable
data class ProviderConfigMeta(
    val configId: String,
    val channel: String,              // video/text/image（三通道独立 Q6）
    val providerId: String,           // agnes / openai_compat / deepseek / ...
    val model: String,
    val keyMasked: String,            // sk-***abc，UI 展示用
    val extraParams: Map<String, String> = emptyMap(), // generate_audio/分辨率/限速间隔/base_url...
    val isVerified: Boolean = false,
    val updatedAt: Long,
)

/** ── finished_films（成片库，T014 v5）──────────────────────── */
@Serializable
data class FinishedFilmMeta(
    val filmId: String,               // 规则："{episodeId}"
    val episodeId: String,
    val projectId: String,
    val fileUri: String,
    val fileSize: Long,
    val durationSeconds: Double,
    val strategy: String,             // CONCAT_COPY / NORMALIZE / SEGMENTED
    val partsUrisJson: String = "[]",
    val colorGrade: String = "CINEMA",
    val assembledAt: Long,
    val updatedAt: Long,
)

/** ── AI 全托管恢复位点（T014 §4.2，写入 episodes.stage_flags）── */
object AiStageFlags {
    const val AI_MANAGED = "ai_managed"          // "true"
    const val LAST_SUCCESS_STAGE = "last_success_stage" // PipelineStage5.name
}