package com.legado.drama.data

import com.legado.drama.data.dao.RenderTaskDao
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.engine.model.ShotMeta
import com.legado.drama.engine.queue.CheckpointEntry
import com.legado.drama.engine.queue.CheckpointMergeLogic
import com.legado.drama.engine.queue.CheckpointStore
import com.legado.drama.engine.queue.EpisodeCheckpoint
import com.legado.drama.engine.queue.ShotState
import java.io.File

/**
 * Room 版 CheckpointStore（架构文档 §3 + §7.3）：
 * 以 render_tasks 表为权威态，load-or-merge 纯逻辑复用引擎 CheckpointMergeLogic。
 */
class RoomCheckpointStore(
    private val dao: RenderTaskDao,
) : CheckpointStore {

    override suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint {
        val existing = dao.listByEpisode(episodeId)
            .map { it.toCheckpointEntry() }
        val merged = CheckpointMergeLogic.merge(existing, shots) { entry ->
            entry.localFileUri?.let { File(it).exists() } ?: false
        }
        // 持久化（含被重置/新增的镜像）
        dao.upsertAll(merged.map { it.toEntity(episodeId) })
        return EpisodeCheckpoint(episodeId = episodeId, entries = merged)
    }

    override suspend fun markSubmitted(shotId: String, providerTaskId: String) {
        val cur = dao.get(shotId)
        dao.upsert(
            (cur ?: RenderTaskEntity(shotId = shotId, episodeId = "", state = ShotState.SUBMITTED.name))
                .copy(
                    state = ShotState.SUBMITTED.name,
                    providerTaskId = providerTaskId,
                    attempt = (cur?.attempt ?: 0) + 1,
                    submittedAt = System.currentTimeMillis(),
                ),
        )
    }

    override suspend fun markCompleted(shotId: String, localFileUri: String) {
        val cur = dao.get(shotId)
        val fileSize = File(localFileUri).length()
        dao.upsert(
            (cur ?: RenderTaskEntity(shotId = shotId, episodeId = "", state = ShotState.COMPLETED.name))
                .copy(
                    state = ShotState.COMPLETED.name,
                    localFileUri = localFileUri,
                    fileSize = fileSize,
                    completedAt = System.currentTimeMillis(),
                ),
        )
    }

    override suspend fun markFailed(shotId: String, reason: String) {
        val cur = dao.get(shotId)
        dao.upsert(
            (cur ?: RenderTaskEntity(shotId = shotId, episodeId = "", state = ShotState.FAILED.name))
                .copy(state = ShotState.FAILED.name, failReason = reason),
        )
    }

    override suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry> {
        return dao.listByEpisode(episodeId)
            .filter { it.state == ShotState.SUBMITTED.name }
            .map { it.toCheckpointEntry() }
    }

    private fun RenderTaskEntity.toCheckpointEntry() = CheckpointEntry(
        shotId = shotId,
        episodeId = episodeId,
        state = ShotState.valueOf(state),
        providerTaskId = providerTaskId,
        attempt = attempt,
        blockedReason = blockedReason,
        failReason = failReason,
        localFileUri = localFileUri,
        fileSize = fileSize,
        submittedAt = submittedAt,
        completedAt = completedAt,
    )

    private fun CheckpointEntry.toEntity(episodeId: String) = RenderTaskEntity(
        shotId = shotId,
        episodeId = this.episodeId.ifEmpty { episodeId },
        state = state.name,
        providerTaskId = providerTaskId,
        attempt = attempt,
        blockedReason = blockedReason,
        failReason = failReason,
        localFileUri = localFileUri,
        fileSize = fileSize,
        submittedAt = submittedAt,
        completedAt = completedAt,
    )
}