package com.legado.drama.engine.router

import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.TextProvider

/**
 * 文本模型路由（T014-arch.md §2.3，Q4：多模型并存）：
 * App 内所有注册推理模型(OpenAI 兼容)均可作为"大脑"。
 */
interface TextModelRouter {

    /** 已注册且可用作"文本大脑"的模型列表（设置页单选数据来源） */
    fun registeredTextModels(): List<TextModelEntry>

    /** 当前生效的文本模型 id */
    fun activeTextModelId(): String

    /** 切换当前生效模型（Key 各自保存，随时互切，Q4） */
    suspend fun setActiveTextModel(modelId: String): Result<Unit>

    /** 测试连通（对应设置页「测试连通」按钮） */
    suspend fun validate(modelId: String): Result<ConnectionInfo>

    /**
     * 解析为一次可注入密钥的 TextProvider：
     * - "agnes" 前缀 → 走 AgnesProvider（内部再 pickTextModel 自动选模）
     * - 其他 → 走 OpenAI 兼容适配器（base_url 从 provider_configs 读）
     */
    suspend fun resolve(modelId: String): TextProvider

    data class TextModelEntry(
        val modelId: String,          // "deepseek-chat" / "agnes-2.5-flash" / ...
        val label: String,            // UI 展示：DeepSeek / Agnes 文本 2.5 Flash
        val providerId: String,       // "deepseek" / "agnes" / "openai_compat"
        val baseUrl: String,          // 如 "https://api.deepseek.com/v1"
        val keyMasked: String?,       // 已存 Key 掩码；null=未配置
        val isVerified: Boolean,      // 是否通过 validate
    )
}

/** DeepSeek 默认坐标（T014 §2.3 写入 provider_configs.extra_params） */
object DeepSeekDefaults {
    const val PROVIDER_ID = "deepseek"
    const val BASE_URL = "https://api.deepseek.com/v1"
    const val MODEL = "deepseek-chat"
    const val ENABLE_THINKING = false
}