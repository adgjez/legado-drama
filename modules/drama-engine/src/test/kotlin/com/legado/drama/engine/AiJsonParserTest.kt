package com.legado.drama.engine

import com.legado.drama.engine.orchestrator.AiJsonParser
import com.legado.drama.engine.orchestrator.AssignedShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 结构化输出解析 + 资产名绑定单测（AI 全托管五阶段的可恢复性/解析正确性）。
 */
class AiJsonParserTest {

    // ── extractJsonBlock：容忍 markdown 围栏与前后杂文本 ──
    @Test
    fun `extractJsonBlock digs object out of markdown fence`() {
        val raw = """
            好的，以下是提取结果：
            ```json
            {"characters":[{"name":"林小满","desc":"少女"}],"scenes":[],"props":[]}
            ```
            请查收。
        """.trimIndent()
        val block = AiJsonParser.extractJsonBlock(raw)
        assertTrue(block != null && block.startsWith("{"))
        assertTrue(block!!.contains("\"林小满\""))
    }

    @Test
    fun `extractJsonBlock returns null when no json`() {
        assertNull(AiJsonParser.extractJsonBlock("模型不可用，无输出"))
    }

    // ── parseAssets：角色/场景/道具分类 ──
    @Test
    fun `parseAssets splits characters scenes props`() {
        val raw = """
            {"characters":[{"name":"林小满","desc":"主角"},{"name":"顾北辰","desc":"男主"}],
             "scenes":[{"name":"老宅正厅","desc":"中式古宅"}],
             "props":[{"name":"玉佩","desc":"传家宝"}]}
        """.trimIndent()
        val specs = AiJsonParser.parseAssets(raw)
        assertEquals(4, specs.size)
        assertEquals("character", specs.first { it.name == "林小满" }.kind)
        assertEquals("scene", specs.first { it.name == "老宅正厅" }.kind)
        assertEquals("prop", specs.first { it.name == "玉佩" }.kind)
    }

    @Test
    fun `parseAssets tolerates garbage`() {
        assertTrue(AiJsonParser.parseAssets("完全没有 JSON 的废话").isEmpty())
    }

    // ── parseStoryboard：分镜数组 ──
    @Test
    fun `parseStoryboard extracts shots with asset refs`() {
        val raw = """
            [{"dialogue":"你不该来","action":"推门而入","characters":["林小满"],"scene":"老宅正厅"},
             {"narration":"他望向窗外","characters":["顾北辰"],"scene":"老宅正厅"}]
        """.trimIndent()
        val shots = AiJsonParser.parseStoryboard(raw)
        assertEquals(2, shots.size)
        assertEquals("林小满", shots[0].characters.first())
        assertEquals("老宅正厅", shots[0].scene)
        assertEquals(1, shots[0].no)
        assertEquals(2, shots[1].no)
    }

    // ── assignShotAssets：首尾帧绑定 + 缺失名标记 ──
    @Test
    fun `assignShotAssets binds first frame to character and last to scene`() {
        val assets = mapOf(
            "林小满" to "a_char_1",
            "顾北辰" to "a_char_2",
            "老宅正厅" to "a_scene_1",
        )
        val drafts = listOf(
            com.legado.drama.engine.orchestrator.StoryboardShotDraft(
                no = 1, dialogue = "你不该来", characters = listOf("林小满"), scene = "老宅正厅",
            ),
        )
        val assigned: List<AssignedShot> = AiJsonParser.assignShotAssets(drafts, assets)
        assertEquals(listOf("a_char_1"), assigned[0].firstAssetIds)
        assertEquals(listOf("a_scene_1"), assigned[0].lastAssetIds)
        assertTrue(assigned[0].unresolvedNames.isEmpty())
    }

    @Test
    fun `assignShotAssets marks unresolved names and falls back to second character`() {
        val assets = mapOf("林小满" to "a_char_1", "顾北辰" to "a_char_2")
        val drafts = listOf(
            com.legado.drama.engine.orchestrator.StoryboardShotDraft(
                no = 1, action = "对峙", characters = listOf("林小满", "顾北辰", "不存在的角色"), scene = null,
            ),
        )
        val assigned = AiJsonParser.assignShotAssets(drafts, assets)
        assertEquals(listOf("a_char_1"), assigned[0].firstAssetIds)
        assertEquals(listOf("a_char_2"), assigned[0].lastAssetIds)
        assertTrue(assigned[0].unresolvedNames.contains("不存在的角色"))
    }

    // ── assetNameFromPrompt / prefixFor：资产名回读 ──
    @Test
    fun `assetNameFromPrompt parses prefixed prompts`() {
        assertEquals("林小满", AiJsonParser.assetNameFromPrompt("角色卡:林小满。少女，扎马尾。"))
        assertEquals("老宅正厅", AiJsonParser.assetNameFromPrompt("场景卡:老宅正厅。中式古宅。"))
        assertNull(AiJsonParser.assetNameFromPrompt("无前缀的 prompt"))
    }

    @Test
    fun `prefixFor maps kinds`() {
        assertEquals(AiJsonParser.PREFIX_CHARACTER, AiJsonParser.prefixFor("character"))
        assertEquals(AiJsonParser.PREFIX_SCENE, AiJsonParser.prefixFor("scene"))
        assertEquals(AiJsonParser.PREFIX_PROP, AiJsonParser.prefixFor("prop"))
        assertEquals("", AiJsonParser.prefixFor("unknown"))
    }
}