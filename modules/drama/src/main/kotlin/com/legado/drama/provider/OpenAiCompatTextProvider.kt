package com.legado.drama.provider

import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ChatResponse
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.TextProvider
import com.legado.drama.engine.provider.ModelSpec
import com.legado.drama.engine.router.DeepSeekDefaults
import com.legado.drama.engine.security.KeyVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
 * OpenAI 兼容文本通道（T014 §2.3 TextModelRouter 的默认实现，
 * DeepSeek 默认坐标：base=https://api.deepseek.com/v1，model=deepseek-chat，
 * enable_thinking=false）。支持任意 OpenAI 兼容推理模型（Q4：多模型并存）。
 */
class OpenAiCompatTextProvider(
    private val keyVault: KeyVault,
    private val baseUrl: String = DeepSeekDefaults.BASE_URL,
) : TextProvider {

    override val id: String = DeepSeekDefaults.PROVIDER_ID

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

    override suspend fun validateKey(key: String): Result<ConnectionInfo> {
        // 最快连通验证：models 列表接口（GET /models）
        return runCatching {
            val resp = client.get("$baseUrl/models") { auth(key) }
            ConnectionInfo(
                ok = resp.status.value < 500,
                message = if (resp.status.value < 500) "文本通道连通 OK" else "服务异常 HTTP ${resp.status.value}",
                model = DeepSeekDefaults.MODEL,
            )
        }
    }

    override suspend fun chat(req: ChatRequest): ChatResponse {
        val key = keyVault.load(DeepSeekDefaults.PROVIDER_ID)
        if (key.isBlank()) throw ProviderError.AuthError("未配置 ${DeepSeekDefaults.PROVIDER_ID} Key")
        val resp = client.post("$baseUrl/chat/completions") {
            auth(key)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "model" to (req.model.ifBlank { DeepSeekDefaults.MODEL }),
                    "messages" to req.messages.map { mapOf("role" to it.role, "content" to it.content) },
                    "max_tokens" to (req.maxTokens ?: 2048),
                    "enable_thinking" to req.enableThinking,
                    "temperature" to (req.temperature ?: 0.8),
                ),
            )
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("文本被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("Key 无效（HTTP ${code.value}）")
        if (code.value >= 400) throw ProviderError.ValidationError("文本请求被拒（HTTP ${code.value}）: ${resp.bodyAsText()}")
        val parsed = json.decodeFromString<OpenAiChatResponseDto>(resp.body<String>())
        return ChatResponse(
            content = parsed.choices.firstOrNull()?.message?.content ?: "",
            model = parsed.model ?: req.model,
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(key: String) {
        header("Authorization", "Bearer $key")
    }

    companion object {
        const val PROVIDER_LABEL = "DeepSeek / OpenAI 兼容"
    }
}

@Serializable
private data class OpenAiChatResponseDto(
    val choices: List<OpenAiChoiceDto> = emptyList(),
    val model: String? = null,
    val usage: OpenAiUsageDto? = null,
)

@Serializable
private data class OpenAiChoiceDto(val message: OpenAiMessageDto? = null)

@Serializable
private data class OpenAiMessageDto(val content: String? = null)

@Serializable
private data class OpenAiUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)