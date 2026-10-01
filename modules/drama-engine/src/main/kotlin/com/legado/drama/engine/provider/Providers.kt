package com.legado.drama.engine.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * 供应商三通道抽象（架构文档 §3）：
 * VideoProvider / TextProvider / ImageProvider。
 * 全部为 suspend 签名级接口，实现可替换（agv/agnes/kling/openai_compat...）。
 */

/** 模型规格，context 窗口等用于自动选模（Agnes 按输入规模路由 512K/256K） */
@Serializable
data class ModelSpec(
    val id: String,             // "agnes-2.5-flash" / "deepseek-chat" / ...
    val label: String,          // UI 展示名
    val providerId: String,     // "agnes" / "deepseek" / ...
    val baseUrl: String,
    val maxContextTokens: Long? = null,
    val supportsVision: Boolean = false,
    val supportsKeyframes: Boolean = false,
    val supportsAudio: Boolean = false,
)

/** validateKey 连通测试结果 */
@Serializable
data class ConnectionInfo(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long? = null,
    val model: String? = null,       // 实际协商到的模型
    val accountTier: String? = null, // 可选：计价层
)

// ───────────────────────── 视频通道 ─────────────────────────

@Serializable
data class VideoSubmitRequest(
    val shotId: String,
    val prompt: String,               // 已含中文配音主导开头 + 显式中文指令（Q9）
    val negativePrompt: String? = null,
    val firstImageUri: String? = null, // keyframes 模式首帧(data URI)
    val lastImageUri: String? = null,  // 尾帧；两者齐备才发 mode=keyframes
    val width: Int = 448,
    val height: Int = 832,
    val numFrames: Int = 121,
    val frameRate: Float = 24f,
    val generateAudio: Boolean = true,
)

sealed interface PollResult {
    data class InProgress(val progress: Int?) : PollResult
    data class Completed(val videoUrl: String) : PollResult
    data class Failed(val reason: String) : PollResult
}

interface VideoProvider {
    val id: String // "agnes" / 未来 "kling"...
    /** 测试连通（最小成本请求）。Key 由 KeyVault 按 configId 取得 */
    suspend fun validateKey(key: String): Result<ConnectionInfo>
    fun listModels(): List<ModelSpec>
    /**
     * 提交一镜视频任务。实现内部必须：
     * ① 先过 120s 提交限速门（RateGate）
     * ② keyframes 双帧模式 + generate_audio=true + 中文配音指令注入（决议 Q9）
     * 返回 providerTaskId(video_id)。调用方拿到后【立即】落库 submitted 态。
     */
    suspend fun submitVideo(req: VideoSubmitRequest): String
    /** 轮询任务。返回终态(completed带url / failed带reason)或进行中(progress) */
    suspend fun pollResult(providerTaskId: String): PollResult
}

// ───────────────────────── 文本通道 ─────────────────────────

@Serializable
data class ChatMessage(
    val role: String,  // system / user / assistant
    val content: String,
    /** 可选图像入参（data URI 或 http URL）；非空时 Provider 按 OpenAI 视觉格式组装 content（对齐源 Models.ChatMessage） */
    val imageUrl: String? = null,
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val maxTokens: Int? = null,
    val enableThinking: Boolean = false, // 对齐 Agnes/DeepSeek 约定：禁 reasoning 吞 content
    val temperature: Double? = null,
)

@Serializable
data class ChatResponse(
    val content: String,
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val finishReason: String? = null,
)

interface TextProvider { // writer / spec 提取 / G2 审计 / 忠实性比对 / 对话
    val id: String
    suspend fun validateKey(key: String): Result<ConnectionInfo>
    suspend fun chat(req: ChatRequest): ChatResponse // enable_thinking=false 约定

    /**
     * 流式对话（可选能力）：返回逐段文本 Flow。
     * 默认实现回退为非流式 [chat] 单块（保持现有 Provider 无需改动即可支持流式壳）；
     * 支持流式的 Provider 应重写为逐段 emit，协议约定与 [chat] 一致（enable_thinking=false）。
     */
    fun streamChat(req: ChatRequest): Flow<String> = flow {
        emit(chat(req).content)
    }
}

// ───────────────────────── 图像通道 ─────────────────────────

@Serializable
data class ImageGenRequest(
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int = 1024,
    val height: Int = 1024,
    val count: Int = 1,              // 6pose 包分次请求
    val referenceUri: String? = null, // i2i 合成时传入
)

interface ImageProvider {
    val id: String
    suspend fun validateKey(key: String): Result<ConnectionInfo>
    /** 返回 url 或 data uri */
    suspend fun generateImage(req: ImageGenRequest): String
}

// ───────────────────────── 错误分类 ─────────────────────────

/**
 * 错误分类（继承 pavo 语义）：
 * AuthError    → 401，抛后队列自动 pause + 引导回设置页
 * QuotaError   → 429，抛后专用退避循环（base 30s cap 180s 最多 3 次）；GET 轮询遇 429 直接上抛暂停队列
 * ValidationError → 4xx 参数错误，不重试
 * TransientError → 5xx，指数退避 2s×2^n ≤3 次
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthError(message: String) : ProviderError(message)
    class QuotaError(message: String, val retryAfterSeconds: Long? = null) : ProviderError(message)
    class ValidationError(message: String) : ProviderError(message)
    class TransientError(message: String, cause: Throwable? = null) : ProviderError(message, cause)
    class NetworkUnavailableError(message: String, cause: Throwable? = null) : ProviderError(message, cause)
}