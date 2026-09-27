package com.legado.drama.engine

import com.legado.drama.engine.gate.DefaultEraDetector
import com.legado.drama.engine.gate.DefaultFidelityGate
import com.legado.drama.engine.gate.StylePresets
import com.legado.drama.engine.model.ShotMeta
import com.legado.drama.engine.queue.PollPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-FIX F3 / HANDOVER 时代红线 + 忠实性 + 轮询策略回归锁：
 *  - 现代剧 → eraKey=modern（负向禁古装、正向不含「深衣曲裾」）
 *  - 西汉剧 → eraKey=han（含汉约束与 ANCIENT_NEGATIVE）
 *  - LLM 断代优先于规则；无 LLM 时规则兜底命中
 *  - FidelityGate 单镜提交前阻断编造台词 / 未绑定资产
 *  - PollPolicy 自适应间隔 30s→60s、取回上限 FETCH_RETRY_MAX
 */
class EraFidelityPollTest {

    // ── 时代红线：规则兜底（无 LLM）──

    @Test
    fun `modern drama infers modern era with no ancient constraints`() = runTest {
        val detector = DefaultEraDetector()
        val script = """
            在现代都市里，林夏在办公室收到微信，她拿起手机打开直播间。
            街道上车水马龙，写字楼灯火通明，她乘地铁赶去医院探望母亲。
        """.trimIndent()

        val preset = detector.detect(script, llmBlock = null)

        assertEquals("modern", preset.eraKey)
        // 验收锚点（P0-FIX F3）：现代剧负向禁古装、正向不含「深衣曲裾」类词
        assertFalse(preset.eraNegative.contains("深衣曲裾"))
        assertTrue(preset.eraNegative.contains("古代服饰"))
        assertFalse(preset.eraPositive.contains("深衣曲裾"))
    }

    @Test
    fun `han drama infers han era with han constraints`() = runTest {
        val detector = DefaultEraDetector()
        val script = """
            西汉年间，未央宫前，大将军霍去病率大军出征。
            长安城头旌旗猎猎，宫中曲裾深衣的宫人列队相送。
        """.trimIndent()

        val preset = detector.detect(script, llmBlock = null)

        assertEquals("han", preset.eraKey)
        assertTrue(preset.eraPositive.contains("深衣") || preset.eraPositive.contains("曲裾"))
        assertTrue(preset.eraNegative.contains("现代"))
        assertFalse(preset.eraPositive.contains("旗袍"))
    }

    @Test
    fun `llm answer wins over rules`() = runTest {
        val detector = DefaultEraDetector()
        // 剧本没有任何朝代词；LLM 明确回答 tang
        val preset = detector.detect("一场普通的相遇。", llmBlock = { _ -> "tang" })
        assertEquals("tang", preset.eraKey)
    }

    @Test
    fun `unknown era falls back to modern`() = runTest {
        val detector = DefaultEraDetector()
        val preset = detector.detect("一片空白。", llmBlock = null)
        assertEquals("modern", preset.eraKey)
    }

    // ── StylePreset 表完整性：8 朝代全量 ──

    @Test
    fun `style presets cover all 8 eras with poses`() {
        val keys = StylePresets.ALL.map { it.eraKey }
        assertEquals(
            listOf("han", "tang", "song", "ming", "qing", "republic", "modern", "any"),
            keys,
        )
        StylePresets.ALL.forEach { p ->
            assertTrue("${p.eraKey} 缺 6 姿态", p.characterPoses.size == 6)
            assertTrue("${p.eraKey} 缺正向约束", p.eraPositive.isNotBlank())
        }
        // presetFor 未知 key 兜底架空
        assertEquals("any", StylePresets.presetFor("nope").eraKey)
        assertEquals("han", StylePresets.presetFor("han").eraKey)
    }

    // ── FidelityGate：提交前忠实性 ──

    @Test
    fun `fidelity blocks fabricated dialogue and unbound asset`() = runTest {
        val gate = DefaultFidelityGate()
        val script = "他推开门。她说：你不该来。"
        val shot = ShotMeta(
            shotId = "s1", shotNo = 1,
            dialogue = "这句话剧本里从来没有",          // 编造台词
            firstAssetIds = listOf("ghost_asset"),       // 未绑定资产
        )
        val report = gate.checkShot(shot, script) { setOf("role1") }
        assertFalse(report.passed)
        assertTrue(report.issues.any { it.rule == "台词忠实性" })
        assertTrue(report.issues.any { it.rule == "资产原样" })
    }

    @Test
    fun `fidelity passes faithful shot`() = runTest {
        val gate = DefaultFidelityGate()
        val script = "他推开门。她说：你不该来。"
        val shot = ShotMeta(
            shotId = "s1", shotNo = 1,
            dialogue = "你不该来",
            action = "他推开门",
            narration = "她站在门口望着他",
            firstAssetIds = listOf("role1"),
        )
        val report = gate.checkShot(shot, script) { setOf("role1") }
        assertTrue("${report.issues}", report.passed)
    }

    // ── PollPolicy：轮询自适应 + 取回上限 ──

    @Test
    fun `poll interval adapts from 30s to 60s after 10 minutes`() {
        val now = 1_000_000L
        assertEquals(30_000L, PollPolicy.adaptivePollIntervalMs(submittedAtMs = now, nowMs = now))
        assertEquals(
            30_000L,
            PollPolicy.adaptivePollIntervalMs(submittedAtMs = now, nowMs = now + 9 * 60_000L),
        )
        assertEquals(
            60_000L,
            PollPolicy.adaptivePollIntervalMs(submittedAtMs = now, nowMs = now + 10 * 60_000L),
        )
        assertEquals(
            60_000L,
            PollPolicy.adaptivePollIntervalMs(submittedAtMs = now, nowMs = now + 8 * 60 * 60_000L),
        )
        // 未知提交时间按早期 30s
        assertEquals(30_000L, PollPolicy.adaptivePollIntervalMs(submittedAtMs = null, nowMs = now))
    }

    @Test
    fun `fetch retry limit stops infinite retry`() {
        assertFalse(PollPolicy.shouldGiveUpAfterFetchFails(7))
        assertTrue(PollPolicy.shouldGiveUpAfterFetchFails(8))
        assertTrue(PollPolicy.shouldGiveUpAfterFetchFails(99))
        assertEquals(8, PollPolicy.FETCH_RETRY_MAX)
    }
}