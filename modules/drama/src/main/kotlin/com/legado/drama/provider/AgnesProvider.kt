package com.legado.drama.provider

import com.legado.drama.ChineseDubPrompt
import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.ChatResponse
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.ImageGenRequest
import com.legado.drama.engine.provider.ImageProvider
import com.legado.drama.engine.provider.ModelSpec
import com.legado.drama.engine.provider.PollResult
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.TextProvider
import com.legado.drama.engine.provider.VideoProvider
import com.legado.drama.engine.provider.VideoSubmitRequest
import com.legado.drama.engine.queue.ChannelKind
import com.legado.drama.engine.queue.RateGate
import com.legado.drama.engine.security.KeyVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Agnes 三通道 Provider（架构文档 §1.2 Q1/Q6/Q9 + §4.1 + 决策 S1 实测）：
 * - 直连 apihub.agnes-ai.com（S1 冒烟已通过，无代理层）；中国站走 api.agnes-ai.cn
 * - 站点分池（对齐源工程 AgnesRegion）：国际站/中国站 Key 独立，见 agnesScopedConfigId()
 * - Video：提交前先过速率门 → keyframes 双帧 + generate_audio + 中文配音指令
 * - 429 长退避（base 30s cap 180s ≤3 次）；5xx 指数退避（2s×2^n ≤3 次）
 * - 参数前置校验（帧数 8n+1、尺寸 64 倍数、fps 1..60）
 * HTTP 契约字段以官方 API 为准；文档可核字段（video_id/status/in_progress/progress）已实现。
 */
class AgnesProvider(
    private val rateGate: RateGate,
    private val keyVault: KeyVault,
    private val baseUrl: String,
    @Volatile var region: AgnesRegion = AgnesRegion.INTERNATIONAL,
) : VideoProvider, TextProvider, ImageProvider {

    override val id: String = PROVIDER_ID

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(Logging) { level = LogLevel.INFO }
        expectSuccess = false // 手动处理状态码以分类错误
    }

    /** Key 维度按站点分池：国际站 "agnes"，中国站 "agnes-cn" */
    private val configIdKey: String get() = agnesScopedConfigId(PROVIDER_ID, region)

    /**
     * 独立图像 Key 维度（对齐源工程 CONFIG_IMAGE="agnes-image"，与文本 agnes / 视频 agnes-video 分开保存）：
     * 国际站 "agnes-image"，中国站 "agnes-image-cn"。
     */
    private val imageConfigIdKey: String get() = agnesScopedConfigId(CONFIG_IMAGE, region)

    /**
     * 生效基址：中国站且 baseUrl 仍为国际站默认值（用户未自定义）→ 切中国站根域；
     * 其余（用户自定义 / 国际站）尊重配置。legado 根域语义不带 /v1，路径由调用处拼接。
     */
    private val effectiveBaseUrl: String
        get() = if (region == AgnesRegion.CHINA && baseUrl == DEFAULT_BASE) CHINA_BASE_URL else baseUrl

    override suspend fun validateKey(key: String): Result<ConnectionInfo> {
        return runCatching {
            // 最小成本连通：GET 根/健康检查；失败按 401/429 分类
            val resp = withRetry429 {
                client.get("$effectiveBaseUrl/") { auth(key) }
            }
            ConnectionInfo(
                ok = resp.status.value < 500,
                message = if (resp.status.value < 500) "Agnes 连通 OK" else "服务异常 HTTP ${resp.status.value}",
                model = DEFAULT_VIDEO_MODEL,
            )
        }.onFailure { return Result.failure(it) }
    }

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec(
            id = DEFAULT_VIDEO_MODEL, label = "Agnes 视频 2.5 Flash",
            providerId = PROVIDER_ID, baseUrl = effectiveBaseUrl,
            supportsKeyframes = true, supportsAudio = true,
        ),
        ModelSpec(
            id = TEXT_MODEL, label = "Agnes 文本 2.5 Flash",
            providerId = PROVIDER_ID, baseUrl = effectiveBaseUrl,
        ),
    )

    // ── VideoProvider ──
    override suspend fun submitVideo(req: VideoSubmitRequest): String {
        // ① 120s 提交限速门（架构 §4.1）
        rateGate.awaitSlot(ChannelKind.VIDEO)
        // ② 参数前置校验（越界直接 ValidationError，不浪费配额）
        validateVideoParams(req)
        // ③ 拼 prompt：中文配音指令注入（决议 Q9：台词前置主导 + 显式中文指令）
        val finalPrompt = ChineseDubPrompt.attach(req.prompt)
        val key = keyVault.load(configIdKey)
        if (key.isBlank()) throw ProviderError.AuthError("未配置 Agnes Key，请先在设置页填写")

        val body = VideoSubmitBody(
            prompt = finalPrompt,
            negativePrompt = req.negativePrompt,
            firstImage = req.firstImageUri,
            lastImage = req.lastImageUri,
            width = req.width,
            height = req.height,
            numFrames = req.numFrames,
            frameRate = req.frameRate,
            generateAudio = req.generateAudio,
            mode = if (req.firstImageUri != null && req.lastImageUri != null) "keyframes" else null,
        )
        val resp = withRetry429 {
            client.post("$effectiveBaseUrl/videos") {
                auth(key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("提交被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("Key 无效或过期（HTTP ${code.value}）")
        if (code.value >= 500) throw ProviderError.TransientError("提交服务异常（HTTP ${code.value}）")
        if (code.value >= 400) throw ProviderError.ValidationError("提交参数被拒（HTTP ${code.value}）: ${resp.bodyAsText()}")

        val parsed = json.decodeFromString<VideoSubmitResponse>(resp.body<String>())
        // video_id 缺失视为服务异常（调用方会走 markSubmitted 防重复付费）
        if (parsed.videoId.isNullOrBlank()) {
            throw ProviderError.TransientError("提交成功但未返回 video_id")
        }
        return parsed.videoId
    }

    override suspend fun pollResult(providerTaskId: String): PollResult {
        val key = keyVault.load(configIdKey)
        if (key.isBlank()) throw ProviderError.AuthError("未配置 Agnes Key")
        val resp = withRetry429 {
            client.get("$effectiveBaseUrl/agnesapi") {
                auth(key)
                parameter("video_id", providerTaskId)
            }
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("轮询被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("Key 无效（HTTP ${code.value}）")
        if (code.value >= 500) throw ProviderError.TransientError("轮询服务异常（HTTP ${code.value}）")

        val parsed = json.decodeFromString<VideoPollResponse>(resp.body<String>())
        return when (parsed.status) {
            "completed", "succeeded", "done" -> PollResult.Completed(parsed.videoUrl.orEmpty())
            "failed", "error" -> PollResult.Failed(parsed.error ?: "渲染失败")
            else -> PollResult.InProgress(parsed.progress)
        }
    }

    // ── TextProvider（Agnes 文本：OpenAI 兼容子集）──
    override suspend fun chat(req: ChatRequest): ChatResponse {
        rateGate.awaitSlot(ChannelKind.TEXT)
        val key = keyVault.load(configIdKey)
        if (key.isBlank()) throw ProviderError.AuthError("未配置 Agnes Key")
        val resp = withRetry429 {
            client.post("$effectiveBaseUrl/v1/chat/completions") {
                auth(key)
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "model" to (req.model.ifBlank { TEXT_MODEL }),
                        // 多模态：imageUrl 非空时按 OpenAI 视觉格式组装 content（对齐源 AgnesProvider）
                        "messages" to req.messages.map { m ->
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
                        },
                        "max_tokens" to (req.maxTokens ?: 1024),
                    ),
                )
            }
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("文本被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("Key 无效（HTTP ${code.value}）")
        if (code.value >= 400) throw ProviderError.ValidationError("文本请求被拒（HTTP ${code.value}）: ${resp.bodyAsText()}")
        val parsed = json.decodeFromString<OpenAiChatResponse>(resp.body<String>())
        return ChatResponse(
            content = parsed.choices.firstOrNull()?.message?.content ?: "",
            model = parsed.model ?: req.model,
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
        )
    }

    // ── ImageProvider（Agnes 图像）──
    override suspend fun generateImage(req: ImageGenRequest): String {
        rateGate.awaitSlot(ChannelKind.IMAGE)
        // 独立图像 Key 优先（agnesis-image / agnes-image-cn），未配置时回退共享 Agnes Key（agnesis / agnes-cn）
        val key = keyVault.load(imageConfigIdKey).ifBlank { keyVault.load(configIdKey) }
        if (key.isBlank()) throw ProviderError.AuthError("未配置 Agnes 图像 Key")
        val resp = withRetry429 {
            client.post("$effectiveBaseUrl/images/generations") {
                auth(key)
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "prompt" to req.prompt,
                        "negative_prompt" to (req.negativePrompt ?: ""),
                        "width" to req.width,
                        "height" to req.height,
                        "n" to req.count,
                        "reference_image" to req.referenceUri,
                    ),
                )
            }
        }
        val code = resp.status
        if (code.value == 429) throw ProviderError.QuotaError("生图被限流（429）")
        if (code.value == 401 || code.value == 403) throw ProviderError.AuthError("Key 无效（HTTP ${code.value}）")
        if (code.value >= 500) throw ProviderError.TransientError("生图服务异常（HTTP ${code.value}）")
        if (code.value >= 400) throw ProviderError.ValidationError("生图请求被拒（HTTP ${code.value}）: ${resp.bodyAsText()}")
        val parsed = json.decodeFromString<ImageGenResponse>(resp.body<String>())
        return parsed.data.firstOrNull()?.url ?: parsed.data.firstOrNull()?.b64Json
            ?: throw ProviderError.TransientError("生图成功但无返回内容")
    }

    // ── 内部工具 ──
    private fun io.ktor.client.request.HttpRequestBuilder.auth(key: String) {
        header("Authorization", "Bearer $key")
    }

    private fun validateVideoParams(req: VideoSubmitRequest) {
        if (req.numFrames <= 0 || (req.numFrames - 1) % 8 != 0) {
            throw ProviderError.ValidationError("帧数须为 8n+1（当前 ${req.numFrames}）")
        }
        if (req.width % 64 != 0 || req.height % 64 != 0) {
            throw ProviderError.ValidationError("宽高须为 64 的倍数（当前 ${req.width}x${req.height}）")
        }
        if (req.frameRate < 1f || req.frameRate > 60f) {
            throw ProviderError.ValidationError("fps 须在 1..60（当前 ${req.frameRate}）")
        }
    }

    /**
     * 429 长退避 + 5xx 指数退避（架构 §4.1）：
     * 429：base 30s cap 180s ≤3 次；5xx：2s×2^n ≤3 次。
     * 注意：429 退避后仍失败 → 上抛 QuotaError，队列暂停并引导用户。
     */
    private suspend fun withRetry429(block: suspend () -> HttpResponse): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = block()
            if (resp.status.value == 429) {
                attempt++
                if (attempt > MAX_QUOTA_RETRY) throw ProviderError.QuotaError("429 重试 ${attempt - 1} 次仍被限流")
                delay(minOf(30_000L * attempt, 180_000L))
                continue
            }
            if (resp.status.value >= 500) {
                attempt++
                if (attempt > MAX_5XX_RETRY) {
                    return resp // 交由调用方按 TransientError 处理
                }
                delay(2_000L * (1L shl (attempt - 1)))
                continue
            }
            return resp
        }
    }

    companion object {
        const val PROVIDER_ID = "agnes"
        const val DEFAULT_VIDEO_MODEL = "agnes-2.5-flash"
        const val TEXT_MODEL = "agnes-2.5-flash"

        /**
         * 独立图像 Key 维度（对齐源工程 CONFIG_IMAGE="agnes-image"）：
         * 图像通道专用，与文本 agnes / 视频 agnes-video 分开保存，两站各自分池。
         */
        const val CONFIG_IMAGE = "agnes-image"

        const val MAX_QUOTA_RETRY = 3
        const val MAX_5XX_RETRY = 3

        /** 国际站默认基址（根域语义；决策 S1 实测 apihub.agnes-ai.com） */
        const val DEFAULT_BASE = "https://apihub.agnes-ai.com"

        /** 中国站根域（legado 根域风格不带 /v1，路径由调用处拼接） */
        const val CHINA_BASE_URL = "https://api.agnes-ai.cn"
    }
}

// ── 请求/响应 DTO（字段取自决策冒烟记录 + 通用 API 约定，官方字段以文档为准）──

@Serializable
private data class VideoSubmitBody(
    val prompt: String,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("first_image") val firstImage: String? = null,
    @SerialName("last_image") val lastImage: String? = null,
    val width: Int,
    val height: Int,
    @SerialName("num_frames") val numFrames: Int,
    @SerialName("frame_rate") val frameRate: Float,
    @SerialName("generate_audio") val generateAudio: Boolean,
    val mode: String? = null,
)

@Serializable
private data class VideoSubmitResponse(
    @SerialName("video_id") val videoId: String? = null,
    @SerialName("status") val status: String? = null,
)

@Serializable
private data class VideoPollResponse(
    val status: String = "in_progress",
    val progress: Int? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    val error: String? = null,
)

@Serializable
private data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val model: String? = null,
    val usage: OpenAiUsage? = null,
) {
    @Serializable data class OpenAiChoice(val message: OpenAiMessage? = null)
}

@Serializable
private data class OpenAiMessage(val content: String? = null)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
private data class ImageGenResponse(val data: List<ImageData> = emptyList()) {
    @Serializable data class ImageData(
        val url: String? = null,
        @SerialName("b64_json") val b64Json: String? = null,
    )
}