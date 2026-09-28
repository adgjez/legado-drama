package com.legado.drama.orchestration

import com.legado.drama.data.dao.EpisodeDao
import com.legado.drama.data.dao.RenderTaskDao
import com.legado.drama.data.dao.ShotDao
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.engine.gate.BudgetGuard
import com.legado.drama.engine.gate.BudgetUsage
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.ModelSpec
import com.legado.drama.engine.provider.PollResult
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.VideoProvider
import com.legado.drama.engine.provider.VideoSubmitRequest
import com.legado.drama.engine.queue.CheckpointEntry
import com.legado.drama.engine.queue.CheckpointMergeLogic
import com.legado.drama.engine.queue.CheckpointStore
import com.legado.drama.engine.queue.EpisodeCheckpoint
import com.legado.drama.engine.queue.ShotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-⑤ 暂停→恢复路径 JVM 实测：
 * 覆盖 auth（AuthError）、network（QuotaError/429）、budget（预算上限）三类暂停的
 * 触发条件与恢复语义，以及正常路径回归。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderQueuePauseResumeTest {

    // ── Fakes ────────────────────────────────────────────────

    private class FakeVideoProvider(
        var submit: suspend (VideoSubmitRequest) -> String,
        var poll: suspend (String) -> PollResult,
    ) : VideoProvider {
        override val id = "fake"
        val submitted = mutableListOf<String>()
        override suspend fun validateKey(key: String) = Result.success(ConnectionInfo(true, "ok"))
        override fun listModels(): List<ModelSpec> = emptyList()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submitted += req.shotId
            return submit(req)
        }

        override suspend fun pollResult(providerTaskId: String): PollResult = poll(providerTaskId)
    }

    private class FakeCheckpointStore : CheckpointStore {
        val entries = mutableMapOf<String, CheckpointEntry>()
        override suspend fun loadOrMerge(episodeId: String, shots: List<com.legado.drama.engine.model.ShotMeta>): EpisodeCheckpoint {
            val merged = CheckpointMergeLogic.merge(entries.values.toList(), shots)
            entries.clear()
            merged.forEach { entries[it.shotId] = it }
            return EpisodeCheckpoint(episodeId, merged)
        }

        override suspend fun markSubmitted(shotId: String, providerTaskId: String) {
            entries[shotId] = (entries[shotId] ?: CheckpointEntry(shotId, "", ShotState.PENDING))
                .copy(state = ShotState.SUBMITTED, providerTaskId = providerTaskId)
        }

        override suspend fun markCompleted(shotId: String, localFileUri: String) {
            entries[shotId] = (entries[shotId] ?: CheckpointEntry(shotId, "", ShotState.PENDING))
                .copy(state = ShotState.COMPLETED, localFileUri = localFileUri, fileSize = 1)
        }

        override suspend fun markFailed(shotId: String, reason: String) {
            entries[shotId] = (entries[shotId] ?: CheckpointEntry(shotId, "", ShotState.PENDING))
                .copy(state = ShotState.FAILED, failReason = reason)
        }

        override suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry> =
            entries.values.filter { it.state == ShotState.SUBMITTED }
    }

    private class FakeBudgetGuard(initial: BudgetUsage) : BudgetGuard {
        val usageFlow = MutableStateFlow(initial)
        override val usage = usageFlow
        override fun canSubmit(projectId: String): Boolean {
            val u = usageFlow.value
            return if (u.projectId != projectId) true else u.usedShots < u.limitShots
        }

        override fun consumeSubmitted(projectId: String) {
            val u = usageFlow.value
            if (u.projectId == projectId) {
                usageFlow.value = u.copy(usedShots = u.usedShots + 1)
            }
        }
    }

    private class FakeEpisodeDao(var episode: EpisodeEntity?) : EpisodeDao {
        override fun observeByProject(projectId: String) = MutableStateFlow(listOfNotNull(episode))
        override suspend fun listAll(): List<EpisodeEntity> = listOfNotNull(episode)
        override suspend fun listByProject(projectId: String): List<EpisodeEntity> = listOfNotNull(episode)
        override suspend fun get(episodeId: String): EpisodeEntity? = episode?.takeIf { it.episodeId == episodeId }
        override suspend fun upsert(episode: EpisodeEntity) { this.episode = episode }
        override suspend fun setReviewPassed(episodeId: String, passed: Boolean) {}
        override suspend fun setStoryboardReport(episodeId: String, report: String) {}
        override suspend fun setStageFlags(episodeId: String, flags: String) {}
    }

    private class FakeShotDao(var shots: List<ShotEntity> = emptyList()) : ShotDao {
        override suspend fun listByEpisode(episodeId: String): List<ShotEntity> =
            shots.filter { it.episodeId == episodeId }
        override fun observeByEpisode(episodeId: String) =
            MutableStateFlow(shots.filter { it.episodeId == episodeId })
        override suspend fun get(shotId: String): ShotEntity? = shots.firstOrNull { it.shotId == shotId }
        override suspend fun upsertAll(shots: List<ShotEntity>) { this.shots = shots }
        override suspend fun upsert(shot: ShotEntity) { shots = shots.filterNot { it.shotId == shot.shotId } + shot }
        override suspend fun delete(shotId: String) { shots = shots.filterNot { it.shotId == shotId } }
        override suspend fun deleteByEpisode(episodeId: String) { shots = shots.filterNot { it.episodeId == episodeId } }
    }

    private class FakeRenderTaskDao(var tasks: List<RenderTaskEntity> = emptyList()) : RenderTaskDao {
        override suspend fun listByEpisode(episodeId: String): List<RenderTaskEntity> =
            tasks.filter { it.episodeId == episodeId }
        override fun observeByEpisode(episodeId: String) =
            MutableStateFlow(tasks.filter { it.episodeId == episodeId })
        override suspend fun get(shotId: String): RenderTaskEntity? = tasks.firstOrNull { it.shotId == shotId }
        override suspend fun upsert(task: RenderTaskEntity) { tasks = tasks.filterNot { it.shotId == task.shotId } + task }
        override suspend fun upsertAll(tasks: List<RenderTaskEntity>) { this.tasks = tasks }
        override suspend fun countSubmitted(episodeId: String): Int =
            tasks.count { it.episodeId == episodeId && it.state == ShotState.SUBMITTED.name }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.buildQueue(
        provider: FakeVideoProvider,
        checkpoint: FakeCheckpointStore = FakeCheckpointStore(),
        budget: FakeBudgetGuard = FakeBudgetGuard(BudgetUsage("p1", 0, 50)),
        shots: List<ShotEntity> = emptyList(),
        episode: EpisodeEntity = EpisodeEntity("e1", "p1", 1, reviewPassed = true),
    ): DefaultRenderQueue {
        val shotDao = FakeShotDao(shots)
        return DefaultRenderQueue(
            videoProvider = provider,
            checkpoint = checkpoint,
            budget = budget,
            shotDao = shotDao,
            episodeDao = FakeEpisodeDao(episode),
            renderTaskDao = FakeRenderTaskDao(),
            scope = this,
        )
    }

    private fun shot(id: String, no: Int, episode: String = "e1", project: String = "p1") =
        ShotEntity(shotId = id, episodeId = episode, projectId = project, shotNo = no)

    // ── ① auth 暂停（提交时 401）──

    @Test
    fun `auth 暂停在最后一镜时不被后续流程覆盖，且 resume 后重驱动`() = runTest {
        val provider = FakeVideoProvider(
            submit = { throw ProviderError.AuthError("Key 无效") },
            poll = { PollResult.InProgress(0) },
        )
        val checkpoint = FakeCheckpointStore()
        val queue = buildQueue(provider, checkpoint = checkpoint, shots = listOf(shot("s1", 1)))

        queue.enqueueEpisode("e1")
        advanceUntilIdle()

        assertEquals("auth", queue.state.value.pausedReason)
        // auth 暂停时该镜已记 FAILED（Key 无效），检查点不漂移
        assertEquals(ShotState.FAILED, checkpoint.entries["s1"]?.state)
    }

    @Test
    fun `auth 暂停后 resume 清暂停并重新入队驱动下一镜`() = runTest {
        var failFirst = true
        val provider = FakeVideoProvider(
            submit = {
                if (failFirst) {
                    failFirst = false
                    throw ProviderError.AuthError("Key 无效")
                }
                "video_ok"
            },
            poll = { PollResult.InProgress(0) },
        )
        val queue = buildQueue(provider, shots = listOf(shot("s1", 1), shot("s2", 2)))

        queue.enqueueEpisode("e1")
        advanceUntilIdle()
        assertEquals("auth", queue.state.value.pausedReason)
        // 队列暂停后 s2 未被消费
        assertEquals(listOf("s1"), provider.submitted)

        queue.resume(confirmedByUser = false)
        advanceUntilIdle()
        // 换 Key 后恢复：s2 被提交，暂停解除
        assertEquals(listOf("s1", "s2"), provider.submitted)
        assertNull(queue.state.value.pausedReason)
    }

    // ── ② network 暂停（repoll 429）──

    @Test
    fun `network 暂停（已提交镜轮询 429）保留并 resume 后重新轮询`() = runTest {
        var firstPoll = true
        val provider = FakeVideoProvider(
            submit = { "video_1" },
            poll = {
                if (firstPoll) {
                    firstPoll = false
                    throw ProviderError.QuotaError("429 限流")
                }
                PollResult.InProgress(0)
            },
        )
        val checkpoint = FakeCheckpointStore()
        val queue = buildQueue(provider, checkpoint = checkpoint, shots = listOf(shot("s1", 1)))

        queue.enqueueEpisode("e1")
        advanceUntilIdle()
        // 预置镜先提交成功，随后 repoll 遇 429 → network 暂停
        assertEquals("network", queue.state.value.pausedReason)

        queue.resume(confirmedByUser = false)
        advanceUntilIdle()
        // 恢复后不再 429，轮询继续；暂停解除
        assertNull(queue.state.value.pausedReason)
        assertEquals(ShotState.SUBMITTED, checkpoint.entries["s1"]?.state)
    }

    // ── ③ budget 暂停（预算上限，需 confirmedByUser）──

    @Test
    fun `budget 暂停需 confirmedByUser 放行，且确认只放行一次（一次性）`() = runTest {
        // 预算已用完：1/1；两镜待渲染
        val budget = FakeBudgetGuard(BudgetUsage("p1", 1, 1))
        val provider = FakeVideoProvider(submit = { "video_1" }, poll = { PollResult.InProgress(0) })
        val queue = buildQueue(provider, budget = budget, shots = listOf(shot("s1", 1), shot("s2", 2)))

        queue.enqueueEpisode("e1")
        advanceUntilIdle()
        assertEquals("budget", queue.state.value.pausedReason)
        // 未消费：无镜被提交
        assertTrue(provider.submitted.isEmpty())

        // 未确认的普通 resume 不得放行
        queue.resume(confirmedByUser = false)
        advanceUntilIdle()
        assertEquals("budget", queue.state.value.pausedReason)

        // 确认放行（不提升预算）：一次性越过预算门提交 s1；s2 处预算再次超限 → 重新暂停
        queue.resume(confirmedByUser = true)
        advanceUntilIdle()
        assertEquals("budget", queue.state.value.pausedReason)
        assertEquals(listOf("s1"), provider.submitted)

        // 再次确认 → 放行 s2（一次性）
        queue.resume(confirmedByUser = true)
        advanceUntilIdle()
        assertNull(queue.state.value.pausedReason)
        assertEquals(listOf("s1", "s2"), provider.submitted)
    }

    // ── ④ 正常路径回归 ──

    @Test
    fun `正常渲染不暂停，无外因时状态保持 null`() = runTest {
        val provider = FakeVideoProvider(submit = { "video_1" }, poll = { PollResult.InProgress(0) })
        val queue = buildQueue(provider, shots = listOf(shot("s1", 1), shot("s2", 2)))

        queue.enqueueEpisode("e1")
        advanceUntilIdle()

        assertNull(queue.state.value.pausedReason)
        assertEquals(listOf("s1", "s2"), provider.submitted)
    }
}