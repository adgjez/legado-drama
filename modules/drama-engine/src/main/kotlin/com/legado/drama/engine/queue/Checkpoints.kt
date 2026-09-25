package com.legado.drama.engine.queue

import com.legado.drama.engine.model.ShotMeta

/**
 * 断点续传 Checkpoint（架构文档 §3 + §7.3，继承 pavo load-or-merge 语义）。
 */
enum class ShotState {
    PENDING, SUBMITTED, COMPLETED, FAILED, BLOCKED,
}

/** 一镜的 checkpoint 记录（render_tasks 行） */
data class CheckpointEntry(
    val shotId: String,
    val episodeId: String,
    val state: ShotState,
    val providerTaskId: String? = null, // ★video_id，SUBMITTED 即刻写入（防重复付费生死线）
    val attempt: Int = 0,
    val blockedReason: String? = null,
    val failReason: String? = null,
    val localFileUri: String? = null,
    val fileSize: Long = 0L,
    val submittedAt: Long? = null,
    val completedAt: Long? = null,
)

/** 一集的完整 checkpoint（loadOrMerge 返回） */
data class EpisodeCheckpoint(
    val episodeId: String,
    val entries: List<CheckpointEntry>,
) {
    val completedIds: List<String> get() = entries.filter { it.state == ShotState.COMPLETED && it.fileSize > 0L }
        .map { it.shotId }
    val submittedIds: List<String> get() = entries.filter { it.state == ShotState.SUBMITTED }
        .map { it.shotId }
    val toRePoll: List<CheckpointEntry> get() = entries.filter { it.state == ShotState.SUBMITTED }
    val pendingIds: List<String> get() = entries.filter { it.state == ShotState.PENDING }
        .map { it.shotId }
}

/**
 * CheckpointStore（架构文档 §3）：
 * 实现须满足：
 *  - load-or-merge：复用既有 checkpoint，保留 submitted/failed/blocked 权威态，仅补缺失镜；
 *    "completed 但文件缺失/0字节"重置为 pending
 *  - markSubmitted 为 submit 成功后立即同步落盘 video_id（防重复付费的生死线）
 *  - pendingRepoll：submitted 态优先 re-poll 已知 video_id，绝不重新 submit
 */
interface CheckpointStore {
    suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint
    suspend fun markSubmitted(shotId: String, providerTaskId: String)
    suspend fun markCompleted(shotId: String, localFileUri: String) // 校验 size>0
    suspend fun markFailed(shotId: String, reason: String)
    suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry>
}

/**
 * load-or-merge 纯逻辑（JVM 可单测）：
 * 1. 既有 entries 中 COMPLETED 且 fileSize<=0 或文件不存在 → 重置 PENDING
 * 2. SUBMITTED/FAILED/BLOCKED 保留权威态；仅对不同有新增 shot 补 PENDING
 * 3. PENDING 在新扫描中缺失 → 移除（该镜已被删除时）
 */
object CheckpointMergeLogic {

    fun merge(
        existing: List<CheckpointEntry>,
        shots: List<com.legado.drama.engine.model.ShotMeta>,
        fileExists: (CheckpointEntry) -> Boolean = { it.localFileUri != null },
    ): List<CheckpointEntry> {
        val shotIds = shots.map { it.shotId }.toSet()
        val merged = mutableListOf<CheckpointEntry>()
        // 既有记录：保留权威态，重置"假完成"
        for (e in existing) {
            if (e.shotId !in shotIds) continue // 该镜已被删除
            when (e.state) {
                ShotState.COMPLETED ->
                    if (e.fileSize <= 0L || !fileExists(e)) {
                        merged += e.copy(state = ShotState.PENDING, localFileUri = null, fileSize = 0L, completedAt = null)
                    } else {
                        merged += e
                    }
                ShotState.SUBMITTED, ShotState.FAILED, ShotState.BLOCKED -> merged += e
                ShotState.PENDING -> merged += e
            }
        }
        // 仅补缺失镜
        val existingIds = merged.map { it.shotId }.toSet()
        for (s in shots) {
            if (s.shotId !in existingIds) {
                merged += CheckpointEntry(
                    shotId = s.shotId,
                    episodeId = "",
                    state = ShotState.PENDING,
                )
            }
        }
        return merged
    }
}