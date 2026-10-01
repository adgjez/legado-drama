package com.legado.drama.engine.orchestrate

import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ChatResponse
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.TextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAssistantTest {
    @Test
    fun `流式文本与动作结果分离并完成`() = runTest {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : TextProvider {
            override val id = "fake-stream"
            override suspend fun validateKey(key: String): Result<ConnectionInfo> =
                Result.success(ConnectionInfo(ok = true, message = "fake"))
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "fake")
            override fun streamChat(req: ChatRequest): Flow<String> {
                requests += req
                return flowOf("好的，开始处理。\n[ACT] extract_assets", "\n资产已提取")
            }
        }
        val assistant = StreamingAssistant(provider, "fake")
        val chunks = assistant.sayStreaming("提取资产").toList()
        assertTrue(
            "流式文本应含正文: $chunks",
            chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("").contains("好的，开始处理"))
        assertTrue(
            "应发射 ActionComplete extract_assets: $chunks",
            chunks.any { it is StreamChunk.ActionComplete && it.verb == "extract_assets" })
        assertEquals("提取资产", requests.single().messages.last().content)
        assertTrue("结尾应为 Done: $chunks", chunks.last() is StreamChunk.Done)
    }

    @Test
    fun `多轮请求携带上一轮消息`() = runTest {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : TextProvider {
            override val id = "fake-history"
            override suspend fun validateKey(key: String): Result<ConnectionInfo> =
                Result.success(ConnectionInfo(ok = true, message = "fake"))
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "fake")
            override fun streamChat(req: ChatRequest): Flow<String> {
                requests += req
                return flowOf("收到")
            }
        }
        val assistant = StreamingAssistant(provider, "fake")
        assistant.sayStreaming("我想做一个悬疑短剧").toList()
        assistant.sayStreaming("继续这个故事").toList()
        assertEquals(
            listOf("我想做一个悬疑短剧", "继续这个故事"),
            requests[1].messages.filter { it.role == "user" }.map { it.content })
        assertEquals("收到", assistant.history.last().content)
    }

    @Test
    fun `同轮 new_project 成功后 set_script 复用回写上下文`() = runTest {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : TextProvider {
            override val id = "fake-ctx"
            override suspend fun validateKey(key: String): Result<ConnectionInfo> =
                Result.success(ConnectionInfo(ok = true, message = "fake"))
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "fake")
            override fun streamChat(req: ChatRequest): Flow<String> {
                requests += req
                return flowOf(
                    "[ACT] new_project | name=雪夜镖局\n" +
                        "[ACT] set_script | text=第一幕：雪夜，镖局灯火通明。",
                )
            }
        }
        val seen = mutableListOf<Pair<String, ActionStatus>>()
        val assistant = StreamingAssistant(
            provider, "fake",
            envelopeHandler = { env ->
                when (env.verb) {
                    "new_project" -> {
                        seen += env.verb to ActionStatus.SUCCEEDED
                        ActionResult(env.actionId, ActionStatus.SUCCEEDED, "已创建", listOf("p_001"))
                    }
                    "set_script" -> {
                        seen += env.verb to ActionStatus.SUCCEEDED
                        ActionResult(env.actionId, ActionStatus.SUCCEEDED, "已保存", emptyList())
                    }
                    else -> ActionResult(env.actionId, ActionStatus.FAILED, "未知: ${env.verb}")
                }
            },
        )
        val chunks = assistant.sayStreaming("新建项目并写入剧本").toList()
        // 两个动作都成功执行（set_script 因回写上下文通过校验）
        assertEquals(listOf("new_project" to ActionStatus.SUCCEEDED, "set_script" to ActionStatus.SUCCEEDED), seen)
        // Done 回显不含"未完成"
        val done = chunks.filterIsInstance<StreamChunk.Done>().last()
        assertTrue("不应有未完成标记: ${done.fullText}", !done.fullText.contains("未完成"))
    }

    @Test
    fun `未知动作 fail-closed 且回显未完成标记`() = runTest {
        val provider = object : TextProvider {
            override val id = "fake-unknown"
            override suspend fun validateKey(key: String): Result<ConnectionInfo> =
                Result.success(ConnectionInfo(ok = true, message = "fake"))
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "fake")
            override fun streamChat(req: ChatRequest): Flow<String> =
                flowOf("抱歉我做不到。\n[ACT] fly_to_moon")
        }
        val assistant = StreamingAssistant(provider, "fake")
        val chunks = assistant.sayStreaming("飞去月球").toList()
        assertTrue(
            "未知动作应被阻断: $chunks",
            chunks.any {
                it is StreamChunk.ActionComplete &&
                    it.verb == "fly_to_moon" && it.message.contains("未知动作") ||
                    it is StreamChunk.Done && it.fullText.contains("1 项未完成")
            })
    }
}