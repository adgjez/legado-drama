package com.legado.drama.provider.video

import com.legado.drama.engine.provider.PollResult
import com.legado.drama.engine.provider.VideoSubmitRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kling 视频供应商适配器 JVM 单测（P0-③）—— MockEngine 模拟 HTTP，不触网。
 * 覆盖：文生/图生提交端点选择、taskId 编码、轮询三态解析、鉴权 header、错误分类。
 */
class KlingProviderTest {

    private fun provider(engine: MockEngine) = KlingProvider(
        apiKeyProvider = { "sk-kling-test" },
        client = HttpClient(engine),
        rateGate = com.legado.drama.engine.queue.DefaultRateGate(videoIntervalMs = 0L),
        sleeper = {},
    )

    private fun defaultRequest() = VideoSubmitRequest(
        shotId = "shot-1",
        prompt = "一段中文配音的镜头",
        width = 448,
        height = 832,
        numFrames = 121,
        frameRate = 24f,
    )

    // ── 文生视频：无参考图走 text2video 端点，taskId 编码带端点前缀 ──
    @Test
    fun `text2video submit returns endpoint prefixed task id`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/videos/text2video", request.url.encodedPath)
            assertEquals("Bearer sk-kling-test", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"data":{"task_id":"task-abc-123"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        assertEquals("text2video:task-abc-123", p.submitVideo(defaultRequest()))
    }

    // ── 图生视频：带首帧走 image2video，data URI 归一为裸 base64 ──
    @Test
    fun `image2video submit normalizes data uri and keeps endpoint`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/videos/image2video", request.url.encodedPath)
            val body = request.body.toByteArray().toString(Charsets.UTF_8)
            assertTrue("body should contain bare base64 image", body.contains("\"image\":\"aW1n\""))
            respond(
                content = """{"data":{"task_id":"task-img-9"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        val req = defaultRequest().copy(
            firstImageUri = "data:image/png;base64,aW1n",
        )
        assertEquals("image2video:task-img-9", p.submitVideo(req))
    }

    // ── 提交 2xx 但缺 task_id → TransientError（本地无 ReconcileRequired，对齐 AgnesProvider 语义） ──
    @Test
    fun `submit missing task id throws transitive error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"data":{}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        val ex = runCatching { p.submitVideo(defaultRequest()) }.exceptionOrNull()
        assertTrue("should be ProviderError", ex is com.legado.drama.engine.provider.ProviderError)
    }

    // ── 轮询 succeed → Completed(url) ──
    @Test
    fun `poll succeed returns video url`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/videos/image2video/task-img-9", request.url.encodedPath)
            respond(
                content = """{"data":{"task_status":"succeed","task_result":{"videos":[{"url":"https://cdn.kling/v1.mp4"}]}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        val r = p.pollResult("image2video:task-img-9")
        assertTrue(r is PollResult.Completed)
        assertEquals("https://cdn.kling/v1.mp4", (r as PollResult.Completed).videoUrl)
    }

    // ── 轮询 failed → Failed(msg) ──
    @Test
    fun `poll failed returns reason`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"data":{"task_status":"failed","task_status_msg":"模型生成失败"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        val r = p.pollResult("image2video:task-img-9")
        assertTrue(r is PollResult.Failed)
        assertTrue((r as PollResult.Failed).reason.contains("模型生成失败"))
    }

    // ── 轮询 processing → InProgress ──
    @Test
    fun `poll processing returns in progress`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"data":{"task_status":"processing"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        assertTrue(p.pollResult("image2video:task-img-9") is PollResult.InProgress)
    }

    // ── 鉴权失败 401 → AuthError ──
    @Test
    fun `poll 401 throws auth error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = provider(engine)
        val ex = runCatching { p.pollResult("image2video:task-img-9") }.exceptionOrNull()
        assertTrue(ex is com.legado.drama.engine.provider.ProviderError.AuthError)
    }
}