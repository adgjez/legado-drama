package com.legado.drama.engine

import com.legado.drama.engine.gate.DefaultG2Auditor
import com.legado.drama.engine.model.AssetMeta
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ChatResponse
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.TextProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 多模态审计器单测（P0-①：Agnes 文本带图 chat、defects 非空直接拒、失败重试 ≤3、JSON 容错、熔断）。
 * 注入假 TextProvider，不触网。
 */
class DefaultG2AuditorTest {

    private fun asset(assetId: String = "a1", prompt: String = "角色卡:林小满。古装少女。") =
        AssetMeta(assetId = assetId, projectId = "p1", kind = "character", prompt = prompt, updatedAt = 0)

    /** 假文本通道：记录收到的请求；按队列返回 raw（抛异常即模拟调用失败）。 */
    private class FakeTextProvider(
        private val responses: MutableList<() -> String>,
        val seen: MutableList<ChatRequest> = mutableListOf(),
    ) : TextProvider {
        override val id: String = "fake"
        override suspend fun validateKey(key: String): Result<ConnectionInfo> = Result.success(ConnectionInfo(true, "ok"))
        override suspend fun chat(req: ChatRequest): ChatResponse {
            seen += req
            return ChatResponse(content = responses.removeAt(0)(), model = "fake")
        }
    }

    // ── 带图请求构造：imageUrl 走 data URI、content 走质检 prompt ──
    @Test
    fun `audit sends vision chat with data uri image`() = runTest {
        val provider = FakeTextProvider(mutableListOf({ """{"score":0.85,"defects":[]}""" }))
        val auditor = DefaultG2Auditor(provider)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val result = auditor.audit(asset(), png)
        assertTrue(result.passed)
        assertEquals(85.0, result.score, 0.01)
        assertEquals(1, provider.seen.size)
        val msg = provider.seen[0].messages.single()
        assertEquals("user", msg.role)
        assertTrue(msg.imageUrl!!.startsWith("data:image/png;base64,"))
        assertTrue(msg.content.contains("角色卡:林小满"))
    }

    // ── defects 非空 → 硬惩罚直接拒（即使分数高）──
    @Test
    fun `defects non-empty rejects even with high score`() = runTest {
        val provider = FakeTextProvider(mutableListOf({ """{"score":0.95,"defects":["水印","文字"]}""" }))
        val auditor = DefaultG2Auditor(provider)
        val result = auditor.audit(asset(), byteArrayOf(1, 2, 3))
        assertFalse(result.passed)
        assertEquals(listOf("水印", "文字"), result.defects)
    }

    // ── 低于通过线 → 拒 ──
    @Test
    fun `score below threshold rejects`() = runTest {
        val provider = FakeTextProvider(mutableListOf({ """{"score":0.4,"defects":[]}""" }))
        val auditor = DefaultG2Auditor(provider)
        val result = auditor.audit(asset(), byteArrayOf(1, 2, 3))
        assertFalse(result.passed)
        assertEquals(40.0, result.score, 0.01)
    }

    // ── 无图 → 无法审计直接拒，不发请求 ──
    @Test
    fun `null image rejects without calling provider`() = runTest {
        val provider = FakeTextProvider(mutableListOf({ """{"score":0.9,"defects":[]}""" }), mutableListOf())
        val auditor = DefaultG2Auditor(provider)
        val result = auditor.audit(asset(), null)
        assertFalse(result.passed)
        assertEquals(0, provider.seen.size)
    }

    // ── 失败重试：前 2 次调用失败，第 3 次成功 → 通过（≤3）──
    @Test
    fun `retries up to maxAttempts then passes`() = runTest {
        val provider = FakeTextProvider(
            mutableListOf(
                { throw RuntimeException("网络瞬断") },
                { throw RuntimeException("5xx") },
                { """{"score":0.88,"defects":[]}""" },
            ),
        )
        val auditor = DefaultG2Auditor(provider, rules = DefaultG2Auditor.G2AuditRules(maxAttempts = 3))
        val result = auditor.audit(asset(), byteArrayOf(1, 2, 3))
        assertTrue(result.passed)
        assertEquals(3, provider.seen.size)
    }

    // ── 全部失败 → 拒 ──
    @Test
    fun `all attempts fail rejects`() = runTest {
        val provider = FakeTextProvider(
            mutableListOf(
                { throw RuntimeException("e1") },
                { throw RuntimeException("e2") },
                { throw RuntimeException("e3") },
            ),
        )
        val auditor = DefaultG2Auditor(provider, rules = DefaultG2Auditor.G2AuditRules(maxAttempts = 3))
        val result = auditor.audit(asset(), byteArrayOf(1, 2, 3))
        assertFalse(result.passed)
        assertEquals(3, provider.seen.size)
        assertTrue(result.defects.isEmpty())
    }

    // ── JSON 容错：前后废话 + markdown 围栏仍能解析 ──
    @Test
    fun `parse tolerates surrounding text and fences`() {
        val raw = """
            好的，审核结果如下：
            ```json
            {"score":0.9,"notes":"ok","defects":[],"face_ratio":0.3}
            ```
            请查收。
        """.trimIndent()
        val parsed = DefaultG2Auditor.parseScore(raw)
        assertEquals(0.9, parsed.score, 0.001)
        assertTrue(parsed.defects.isEmpty())
        assertEquals(0.3, parsed.faceRatio!!, 0.001)
    }

    @Test
    fun `parse handles nested objects in notes and unparseable fallback`() {
        val parsed = DefaultG2Auditor.parseScore("""{"score":0.75,"notes":{"missing_features":["手部"]},"defects":[]}""")
        assertEquals(0.75, parsed.score, 0.001)
        assertTrue(parsed.notes.contains("手部"))
        val garbage = DefaultG2Auditor.parseScore("完全不是 JSON 的返回文本")
        assertEquals(0.0, garbage.score, 0.001)
        assertTrue(garbage.notes.contains("unparseable"))
    }

    // ── base64 编码正确性（data URI 反解比对）──
    @Test
    fun `base64 encode matches java util decoder`() {
        val bytes = "Hello, G2 审计器！".toByteArray(Charsets.UTF_8)
        val encoded = DefaultG2Auditor.base64Encode(bytes)
        val expected = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals(expected, encoded)
    }

    // ── 输入熔断：超大图 + 长 prompt 估算超限 → 拒且不发请求 ──
    @Test
    fun `overloaded input rejects without calling provider`() = runTest {
        val provider = FakeTextProvider(mutableListOf({ "{}" }), mutableListOf())
        val auditor = DefaultG2Auditor(provider)
        val bigImage = ByteArray(3 * 400_000) // base64 后远超 40 万 token 上限
        val result = auditor.audit(asset(), bigImage)
        assertFalse(result.passed)
        assertEquals(0, provider.seen.size)
    }

    @Test
    fun `estimate tokens counts cjk and ascii`() {
        val text = "中文a" // 2 cjk + 1 ascii(1/4≈0) → 2
        assertEquals(2L, DefaultG2Auditor.estimateTokens(text))
    }

    // ── rules 校验：maxAttempts 越界拒绝构造 ──
    @Test
    fun `rules reject maxAttempts out of range`() {
        var thrown = false
        try {
            DefaultG2Auditor.G2AuditRules(maxAttempts = 0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}