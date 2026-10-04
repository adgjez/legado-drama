package com.legado.drama.engine.assemble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** AssembleStrategySelector 三级策略选择纯逻辑单测（MovieAssembler 契约 §2.4）。 */
class AssembleStrategySelectorTest {

    private fun clip(name: String) = File(name)

    private fun spec(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Float = 30f,
        durationMs: Long = 5_000,
    ) = ClipSpec(width, height, frameRate, hasAudio = true, durationMs = durationMs)

    // ── 1. 全部规格一致 → CONCAT_COPY ──

    @Test
    fun `all same resolution and fps selects CONCAT_COPY`() {
        val clips = listOf(clip("a.mp4"), clip("b.mp4"), clip("c.mp4"))
        val specs = clips.map { spec() }
        assertEquals(
            MovieAssembler.Strategy.CONCAT_COPY,
            AssembleStrategySelector.select(clips, specs),
        )
    }

    // ── 2. 混合分辨率/帧率 → NORMALIZE ──

    @Test
    fun `mixed resolution selects NORMALIZE`() {
        val clips = listOf(clip("a.mp4"), clip("b.mp4"))
        val specs = listOf(spec(width = 1280), spec(width = 1920))
        assertEquals(
            MovieAssembler.Strategy.NORMALIZE,
            AssembleStrategySelector.select(clips, specs),
        )
    }

    @Test
    fun `mixed frame rate selects NORMALIZE`() {
        val clips = listOf(clip("a.mp4"), clip("b.mp4"))
        val specs = listOf(spec(frameRate = 30f), spec(frameRate = 60f))
        assertEquals(
            MovieAssembler.Strategy.NORMALIZE,
            AssembleStrategySelector.select(clips, specs),
        )
    }

    // ── 3. 探测失败（probeResult=null）→ NORMALIZE 兜底 ──

    @Test
    fun `null probe result selects NORMALIZE`() {
        val clips = listOf(clip("a.mp4"), clip("b.mp4"))
        assertEquals(
            MovieAssembler.Strategy.NORMALIZE,
            AssembleStrategySelector.select(clips, null),
        )
    }

    // ── 4. 片段过多（> 8*4=32）→ SEGMENTED ──

    @Test
    fun `over 32 clips selects SEGMENTED even when specs identical`() {
        val clips = (1..33).map { clip("shot-$it.mp4") }
        val specs = clips.map { spec() }
        assertEquals(
            MovieAssembler.Strategy.SEGMENTED,
            AssembleStrategySelector.select(clips, specs),
        )
    }

    @Test
    fun `exactly 32 clips still selects CONCAT_COPY when specs identical`() {
        val clips = (1..32).map { clip("shot-$it.mp4") }
        val specs = clips.map { spec() }
        assertEquals(
            MovieAssembler.Strategy.CONCAT_COPY,
            AssembleStrategySelector.select(clips, specs),
        )
    }

    // ── 5. preferSegmented 强制分段 ──

    @Test
    fun `preferSegmented forces SEGMENTED regardless of size`() {
        val clips = listOf(clip("a.mp4"))
        val specs = clips.map { spec() }
        assertEquals(
            MovieAssembler.Strategy.SEGMENTED,
            AssembleStrategySelector.select(clips, specs, preferSegmented = true),
        )
    }

    // ── 6. 边界：空列表 ──

    @Test
    fun `empty clips with empty probe selects NORMALIZE`() {
        assertEquals(
            MovieAssembler.Strategy.NORMALIZE,
            AssembleStrategySelector.select(emptyList(), emptyList()),
        )
    }
}