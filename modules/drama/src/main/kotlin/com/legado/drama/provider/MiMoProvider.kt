package com.legado.drama.provider

import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ChatResponse
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.TextProvider
import com.legado.drama.engine.security.KeyVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MiMoProvider —— 小米 MiMo 文本通道（OpenAI 兼容，对齐源 core/provider/MiMoProvider.kt）。
 *
 * 只暴露 chat 通道（文本模型作为"大脑"，不承载视频/图像提交），与 DeepSeek 同构：
 * - base_url 按 Key 前缀自动选站：
 *   - `tp-` 前缀 = Token Plan 订阅：https://token-plan-cn.xiaomimimo.com/v1
 *   - `sk-` 前缀 = 按量付费：https://api.xiaomimimo.com/v1
 * - model = mimo-v2.6-pro（默认旗舰；可经构造参数覆盖）
 * - 请求体走 /chat/completions（MiMo 不吃 chat_template_kwargs，故不发 enable_thinking）
 * - 仅实现 TextProvider 接口；不提供 VideoProvider/ImageProvider 通道
 *
 * Key 走 KeyVault "mimo" 维度，独立分池（可再经 provider_configs text 行展示）。
 */
class MiMoProvider(
    private val keyVault: KeyVault,
    /** 模型 ID；默认旗舰 mimo-v2.6-pro */
    private val model: String = MODEL,
) : TextProvider {

    override val id: String = PROVIDER_ID

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
        expectSuccess = false
    }

    override suspend fun validateKey(key: String): Result<ConnectionInfo> = runCatching {
        val t0 = System.currentTimeMillis()
        chatPing(key)
        ConnectionInfo(
            ok = true,
            message = "MiMo 文本通道连通 OK",
            latencyMs = System.currentTimeMillis() - t0,
            model = model,
        )
    }

    override suspend fun chat(req: ChatRequest): ChatResponse {
        val key = keyVault.load(PROVIDER_ID)
        if (key.isBlank()) throw ProviderError.AuthError("未配置 MiMo Key")
        val total = req.messages.sumOf { estimateTokens(it.content) }
        if (total >= TEXT_INPUT_TOKEN_LIMIT) {
            throw ProviderError.ValidationError(
                "context overload: ~${total / 1000}K tokens exceeds ${TEXT_INPUT_TOKEN_LIMIT / 1000}K safe limit",
            )
        }
        val resp = client.post("${baseUrlFor(key)}/chat/completions") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "model" to (req.model.ifBlank { model }),
                    "messages" to req.messages.map { messageJson(it) },
                    "temperature" to (req.temperature ?: 0.8),
                    "max_tokens" to (req.maxTokens ?: 2048),
                ),
            )
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("MiMo 被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("MiMo Key 无效（HTTP ${code.value}）")
        if (code.value >= 400) throw ProviderError.ValidationError("MiMo 请求被拒（HTTP ${code.value}）: ${resp.bodyAsText()}")
        val parsed = json.decodeFromString<MiMoChatResponseDto>(resp.body<String>())
        return ChatResponse(
            content = parsed.choices.firstOrNull()?.message?.content ?: "",
            model = parsed.model ?: req.model.ifBlank { model },
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
        )
    }

    /** 最小成本 chat ping（validateKey 用，对齐源 chatPing） */
    private suspend fun chatPing(key: String) {
        val resp = client.post("${baseUrlFor(key)}/chat/completions") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "model" to model,
                    "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
                    "max_tokens" to 4,
                    "temperature" to 0.0,
                ),
            )
        }
        when {
            resp.status.value == 429 -> throw ProviderError.QuotaError("MiMo 被限流（429）")
            resp.status.value == 401 || resp.status.value == 403 -> throw ProviderError.AuthError("MiMo Key 无效（HTTP ${resp.status.value}）")
            resp.status.value >= 400 -> throw ProviderError.ValidationError("MiMo ping 被拒（HTTP ${resp.status.value}）: ${resp.bodyAsText()}")
            else -> Unit // 2xx：连通 OK
        }
    }

    companion object {
        /** 按量付费（sk- 前缀 Key） */
        const val BASE_URL_PAYG = "https://api.xiaomimimo.com/v1"
        /** Token Plan 订阅（tp- 前缀 Key） */
        const val BASE_URL_TOKEN_PLAN = "https://token-plan-cn.xiaomimimo.com/v1"
        /** 设置页展示用的代表性地址（Token Plan） */
        const val BASE_URL = BASE_URL_TOKEN_PLAN
        const val MODEL = "mimo-v2.6-pro"
        const val PROVIDER_ID = "mimo"
        const val TEXT_INPUT_TOKEN_LIMIT = 230_000L

        /** 中文≈1token、ASCII≈4字符1token 的保守估算（对齐源） */
        fun estimateTokens(text: String): Long {
            var cjk = 0L
            for (c in text) if (c.code > 0x2E80) cjk++
            return cjk + (text.length - cjk) / 4
        }

        /** 按 Key 前缀选网关：tp- = Token Plan，其余（sk- 等）= 按量付费（对齐源 baseUrlFor） */
        fun baseUrlFor(key: String): String =
            if (key.startsWith("tp-")) BASE_URL_TOKEN_PLAN else BASE_URL_PAYG

        /** 单条消息 → OpenAI 兼容消息体（含可选 image_url 多模态，对齐源 messageJson） */
        fun messageJson(m: ChatMessage): Map<String, Any?> =
            if (m.imageUrl != null) {
                mapOf(
                    "role" to m.role,
                    "content" to listOf(
                        mapOf("type" to "text", "text" to m.content),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to m.imageUrl)),
                    ),
                )
            } else {
                mapOf("role" to m.role, "content" to m.content)
            }
    }
}

@Serializable
private data class MiMoChatResponseDto(
    val choices: List<MiMoChoiceDto> = emptyList(),
    val model: String? = null,
    val usage: MiMoUsageDto? = null,
)

@Serializable
private data class MiMoChoiceDto(val message: MiMoMessageDto? = null)

@Serializable
private data class MiMoMessageDto(val content: String? = null)

@Serializable
private data class MiMoUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)