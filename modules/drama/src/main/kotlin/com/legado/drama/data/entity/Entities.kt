package com.legado.drama.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体（架构文档 §5 schema + T014 finished_films v5）：
 * 表结构完全按文档 SQL 定义，从零建 v1 库（不携带历史迁移链）。
 */

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "style_preset") val stylePreset: String = "cinema",
    @ColumnInfo(name = "episode_plan") val episodePlan: Int = 1,
    @ColumnInfo(name = "budget_shots") val budgetShots: Int = 50,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "kind") val kind: String,            // character/scene/prop
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "pose_role") val poseRole: String? = null,
    @ColumnInfo(name = "prompt") val prompt: String,
    @ColumnInfo(name = "file_uri") val fileUri: String? = null,
    @ColumnInfo(name = "remote_url") val remoteUrl: String? = null,
    @ColumnInfo(name = "g1_state") val g1State: String = "none",     // none/pass/rejected
    @ColumnInfo(name = "g2_score") val g2Score: Double? = null,
    @ColumnInfo(name = "g2_defects") val g2Defects: String? = null,
    @ColumnInfo(name = "review_state") val reviewState: String = "none", // none/keep/regen
    @ColumnInfo(name = "reject_reason") val rejectReason: String? = null,
    @ColumnInfo(name = "seed") val seed: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "shots")
data class ShotEntity(
    @PrimaryKey @ColumnInfo(name = "shot_id") val shotId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "shot_no") val shotNo: Int,
    @ColumnInfo(name = "dialogue") val dialogue: String? = null,
    @ColumnInfo(name = "narration") val narration: String? = null,
    @ColumnInfo(name = "action") val action: String? = null,
    @ColumnInfo(name = "beat_ref") val beatRef: String? = null,
    @ColumnInfo(name = "carry_over") val carryOver: String? = null,
    @ColumnInfo(name = "first_asset_ids") val firstAssetIds: String = "[]", // JSON 数组
    @ColumnInfo(name = "last_asset_ids") val lastAssetIds: String = "[]",
    @ColumnInfo(name = "sb_check") val sbCheck: String = "pending",         // 六铁律: pending/pass/error
)

@Entity(tableName = "render_tasks")
data class RenderTaskEntity(
    @PrimaryKey @ColumnInfo(name = "shot_id") val shotId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "state") val state: String,           // PENDING/SUBMITTED/COMPLETED/FAILED/BLOCKED
    @ColumnInfo(name = "provider_task_id") val providerTaskId: String? = null, // video_id 防重复付费
    @ColumnInfo(name = "attempt") val attempt: Int = 0,
    @ColumnInfo(name = "blocked_reason") val blockedReason: String? = null,
    @ColumnInfo(name = "fail_reason") val failReason: String? = null,
    @ColumnInfo(name = "local_file_uri") val localFileUri: String? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0L,
    @ColumnInfo(name = "submitted_at") val submittedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey @ColumnInfo(name = "config_id") val configId: String,
    @ColumnInfo(name = "channel") val channel: String,       // video/text/image
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "key_cipher") val keyCipher: String? = null,  // AES-GCM 密文（Keystore 主密钥）
    @ColumnInfo(name = "key_masked") val keyMasked: String = "",
    @ColumnInfo(name = "extra_params") val extraParams: String = "{}", // JSON（base_url/限速/音频等）
    @ColumnInfo(name = "is_verified") val isVerified: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "ep_no") val epNo: Int,
    @ColumnInfo(name = "script_json") val scriptJson: String? = null,
    @ColumnInfo(name = "storyboard_report") val storyboardReport: String? = null,
    @ColumnInfo(name = "review_passed") val reviewPassed: Boolean = false,
    @ColumnInfo(name = "stage_flags") val stageFlags: String = "{}", // JSON: ai_managed/last_success_stage
)

@Entity(tableName = "finished_films")
data class FinishedFilmEntity(
    @PrimaryKey @ColumnInfo(name = "film_id") val filmId: String,  // 规则："{episodeId}"
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "file_uri") val fileUri: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double,
    @ColumnInfo(name = "strategy") val strategy: String,           // CONCAT_COPY/NORMALIZE/SEGMENTED
    @ColumnInfo(name = "parts_uris_json") val partsUrisJson: String = "[]",
    @ColumnInfo(name = "color_grade") val colorGrade: String = "CINEMA",
    @ColumnInfo(name = "assembled_at") val assembledAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)