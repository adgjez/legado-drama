package com.legado.drama.provider

import com.legado.drama.engine.provider.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiMo 文本通道纯逻辑单测（P0-②：tp-/sk- 前缀选站、token 估算、多模态消息体组装）。
 * 纯 Kotlin 逻辑，不触网。
 */
class MiMoProviderTest {

    // ── 前缀选站：tp- = Token Plan，sk-/其他 = 按量付费 ──
    @Test
    fun `tp prefix routes to token plan`() {
        assertEquals(
            MiMoProvider.BASE_URL_TOKEN_PLAN,
            MiMoProvider.baseUrlFor("tp-abcdef"),
        )
    }

    @Test
    fun `sk prefix routes to payg`() {
        assertEquals(
            MiMoProvider.BASE_URL_PAYG,
            MiMoProvider.baseUrlFor("sk-abcdef"),
        )
    }

    @Test
    fun `unknown prefix falls back to payg`() {
        assertEquals(
            MiMoProvider.BASE_URL_PAYG,
            MiMoProvider.baseUrlFor("xxx-abcdef"),
        )
    }

    // ── token 估算：中文≈1token、ASCII≈4字符1token ──
    @Test
    fun `estimate tokens counts cjk as one and ascii as quarter`() {
        assertEquals(2L, MiMoProvider.estimateTokens("中文ab"))      // 2 + 2/4 = 2
        assertEquals(1L, MiMoProvider.estimateTokens("abcde"))        // 0 + 5/4 = 1（整数除法）
    }

    @Test
    fun `estimate tokens empty is zero`() {
        assertEquals(0L, MiMoProvider.estimateTokens(""))
    }

    // ── 多模态消息体：imageUrl 非空 → OpenAI 视觉格式 content 数组 ──
    @Test
    fun `message with imageUrl assembles vision content array`() {
        val body = MiMoProvider.messageJson(
            ChatMessage(role = "user", content = "评估该图", imageUrl = "data:image/png;base64,AAAA"),
        )
        assertEquals("user", body["role"])
        val content = body["content"] as List<*>
        assertEquals(2, content.size)
        val textPart = content[0] as Map<*, *>
        val imagePart = content[1] as Map<*, *>
        assertEquals("text", textPart["type"])
        assertEquals("评估该图", textPart["text"])
        assertEquals("image_url", imagePart["type"])
        val imageUrl = (imagePart["image_url"] as Map<*, *>)["url"]
        assertEquals("data:image/png;base64,AAAA", imageUrl)
    }

    @Test
    fun `message without image keeps plain string content`() {
        val body = MiMoProvider.messageJson(ChatMessage(role = "user", content = "你好"))
        assertEquals("user", body["role"])
        assertEquals("你好", body["content"])
    }

    // ── 常量契约：模型/站点/Key 维度稳定 ──
    @Test
    fun `constants match contract`() {
        assertEquals("mimo-v2.6-pro", MiMoProvider.MODEL)
        assertEquals("mimo", MiMoProvider.PROVIDER_ID)
        assertTrue(MiMoProvider.BASE_URL_TOKEN_PLAN.contains("token-plan-cn"))
        assertTrue(MiMoProvider.BASE_URL_PAYG.contains("api.xiaomimimo.com"))
    }
}