package com.legado.drama.engine

import com.legado.drama.engine.gate.DefaultAssetGate
import com.legado.drama.engine.gate.DefaultStoryboardGate
import com.legado.drama.engine.model.AssetMeta
import com.legado.drama.engine.model.ShotMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCoreTest {

    // ── G1 资产硬校验 ──
    @Test
    fun `G1 rejects non-square dims and empty file`() {
        val gate = DefaultAssetGate()
        val square = AssetMeta(
            assetId = "a1", projectId = "p1", kind = "character", prompt = "x",
            fileUri = "data:image/png;base64,AAAA", updatedAt = 0,
        )
        val ok = gate.checkG1(square, 1024, 1024)
        assertTrue(ok.passed)

        val nonSquare = gate.checkG1(square, 1024, 768)
        assertFalse(nonSquare.passed)
        assertTrue(nonSquare.reason!!.contains("正方形"))

        val noFile = gate.checkG1(square.copy(fileUri = null), 1024, 1024)
        assertFalse(noFile.passed)
    }

    // ── 六铁律 ──
    @Test
    fun `storyboard gate blocks missing first frame and non-scripted dialogue`() = runTest {
        val validAssets = setOf("role1")
        val gate = DefaultStoryboardGate { validAssets }
        val script = "第一章。他推开那扇门。她说：你不该来。"
        val shots = listOf(
            ShotMeta(
                shotId = "s1", shotNo = 1, dialogue = "你不该来",
                action = "推门", firstAssetIds = listOf("role1"),
            ),
            ShotMeta(
                shotId = "s2", shotNo = 2,
                dialogue = "这句台词剧本里没有",
                firstAssetIds = emptyList(), // 缺首帧
            ),
        )
        val report = gate.check(script, shots)
        assertFalse(report.passed)
        assertTrue(report.errorCount >= 2) // 首帧缺失 + 台词不在剧本
    }

    @Test
    fun `storyboard gate passes well-formed shots`() = runTest {
        val validAssets = setOf("role1", "scene1")
        val gate = DefaultStoryboardGate { validAssets }
        val script = "他推开门。她说：你不该来。"
        val shots = listOf(
            ShotMeta(
                shotId = "s1", shotNo = 1, dialogue = "你不该来",
                action = "推门", firstAssetIds = listOf("role1"), lastAssetIds = listOf("scene1"),
            ),
            ShotMeta(
                shotId = "s2", shotNo = 2, narration = "他望向窗外",
                firstAssetIds = listOf("scene1"),
            ),
        )
        val report = gate.check(script, shots)
        assertTrue("${report.issues}", report.passed)
        assertTrue(report.summary.contains("通过"))
    }

    // ── Checkpoint 合并语义
    @Test
    fun `checkpoint merge resets fake completed and keeps authority states`() {
        val existing = listOf(
            com.legado.drama.engine.queue.CheckpointEntry(
                shotId = "s1", episodeId = "e1",
                state = com.legado.drama.engine.queue.ShotState.COMPLETED,
                localFileUri = "file:///clips/s1.mp4", fileSize = 0L, // 0 字节 → 重置
            ),
            com.legado.drama.engine.queue.CheckpointEntry(
                shotId = "s2", episodeId = "e1",
                state = com.legado.drama.engine.queue.ShotState.SUBMITTED,
                providerTaskId = "video_123",
            ),
            com.legado.drama.engine.queue.CheckpointEntry(
                shotId = "s3", episodeId = "e1",
                state = com.legado.drama.engine.queue.ShotState.BLOCKED,
                blockedReason = "六铁律未过",
            ),
        )
        val merged = com.legado.drama.engine.queue.CheckpointMergeLogic.merge(
            existing,
            listOf(
                ShotMeta(shotId = "s1", shotNo = 1),
                ShotMeta(shotId = "s2", shotNo = 2),
                ShotMeta(shotId = "s3", shotNo = 3),
                ShotMeta(shotId = "s4", shotNo = 4), // 新增镜
            ),
        )
        assertEquals(4, merged.size)
        assertEquals(com.legado.drama.engine.queue.ShotState.PENDING, merged.first { it.shotId == "s1" }.state)
        assertEquals(
            com.legado.drama.engine.queue.ShotState.SUBMITTED,
            merged.first { it.shotId == "s2" }.state,
        )
        assertEquals("video_123", merged.first { it.shotId == "s2" }.providerTaskId)
        assertEquals(com.legado.drama.engine.queue.ShotState.BLOCKED, merged.first { it.shotId == "s3" }.state)
        assertEquals(com.legado.drama.engine.queue.ShotState.PENDING, merged.first { it.shotId == "s4" }.state)
    }

    // ── 预算闸门 ──
    @Test
    fun `budget guard blocks over limit and consumes`() {
        val guard = com.legado.drama.engine.gate.DefaultBudgetGuard(
            com.legado.drama.engine.gate.BudgetUsage(
                projectId = "p1", usedShots = 49, limitShots = 50,
            ),
        )
        assertTrue(guard.canSubmit("p1"))
        guard.consumeSubmitted("p1") // 50
        assertFalse(guard.canSubmit("p1"))
        assertTrue(guard.usage.value.exceeded)
    }

    // ── 进度日志裁剪（T014 R9）──
    @Test
    fun `progress log trimmer caps at max`() {
        val events = (1..600).map {
            com.legado.drama.engine.orchestrator.ProgressEvent(
                stage = com.legado.drama.engine.orchestrator.PipelineStage5.EXTRACT_ASSETS,
                subStep = 0, message = "e$it", elapsedMs = it * 1000L, stageElapsedMs = 0,
            )
        }
        val trimmed = com.legado.drama.engine.orchestrator.ProgressLogTrimmer.trim(events)
        assertEquals(200, trimmed.size)
        assertEquals("e401", trimmed.first().message)
    }

    // ── 成片策略选择 ──
    @Test
    fun `assembler picks concat-copy for identical clips`() {
        val clip = java.io.File("/tmp/clip.mp4")
        val spec = listOf(
            com.legado.drama.engine.assemble.ClipSpec(448, 832, 24f, true, 5000),
            com.legado.drama.engine.assemble.ClipSpec(448, 832, 24f, true, 5000),
        )
        assertEquals(
            com.legado.drama.engine.assemble.MovieAssembler.Strategy.CONCAT_COPY,
            com.legado.drama.engine.assemble.AssembleStrategySelector.select(listOf(clip, clip), spec),
        )
    }

    @Test
    fun `assembler normalizes mixed specs`() {
        val clip = java.io.File("/tmp/clip.mp4")
        val spec = listOf(
            com.legado.drama.engine.assemble.ClipSpec(448, 832, 24f, true, 5000),
            com.legado.drama.engine.assemble.ClipSpec(640, 960, 30f, true, 5000),
        )
        assertEquals(
            com.legado.drama.engine.assemble.MovieAssembler.Strategy.NORMALIZE,
            com.legado.drama.engine.assemble.AssembleStrategySelector.select(listOf(clip, clip), spec),
        )
    }

    // ── Gate 评估 ──
    @Test
    fun `gate report canRender requires all gates`() {
        val ok = com.legado.drama.engine.orchestrator.GateEvaluationLogic.compose(
            assetsGenerated = true, reviewPassed = true, storyboardPassed = true,
            keyValid = true, budgetOk = true,
        )
        assertTrue(ok.canRender)

        val blocked = com.legado.drama.engine.orchestrator.GateEvaluationLogic.compose(
            assetsGenerated = true, reviewPassed = false, storyboardPassed = true,
            keyValid = true, budgetOk = true,
        )
        assertFalse(blocked.canRender)
    }

    // ── Key 掩码 ──
    @Test
    fun `key masker keeps head and tail`() {
        assertEquals("sk-***abc", com.legado.drama.engine.security.KeyMasker.mask("sk-1234567890abc"))
        assertEquals("******", com.legado.drama.engine.security.KeyMasker.mask("abcdef"))
        assertEquals("", com.legado.drama.engine.security.KeyMasker.mask(null))
    }
}