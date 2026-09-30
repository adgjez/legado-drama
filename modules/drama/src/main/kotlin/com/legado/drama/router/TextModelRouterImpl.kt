package com.legado.drama.router

import com.legado.drama.AppGraph
import com.legado.drama.engine.provider.ConnectionInfo
import com.legado.drama.engine.provider.TextProvider
import com.legado.drama.engine.router.DeepSeekDefaults
import com.legado.drama.engine.router.TextModelRouter
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.provider.AgnesRegion
import com.legado.drama.provider.MiMoProvider
import com.legado.drama.provider.agnesScopedConfigId

/**
 * TextModelRouter 实现（T014-arch.md §2.3，决议 Q4 多模型并存）：
 * 注册表 = DeepSeek（默认坐标）+ Agnes 文本，Key 各自保存（KeyVault），随时互切。
 * 路由：deepseek-chat → OpenAiCompatTextProvider；agnes 前缀 → AgnesProvider。
 * Agnes 按站点分池：国际站读 "agnes"、中国站读 "agnes-cn"（对齐源工程 agnesScopedConfigId）。
 * 设置页「文本模型」区块数据源；Key 空时 AiOrchestrator.run 抛 ModelBlocked 阻断。
 */
class TextModelRouterImpl(
    private val graph: AppGraph,
) : TextModelRouter {

    override fun registeredTextModels(): List<TextModelRouter.TextModelEntry> = listOf(
        TextModelRouter.TextModelEntry(
            modelId = DeepSeekDefaults.MODEL,
            label = "DeepSeek Chat",
            providerId = DeepSeekDefaults.PROVIDER_ID,
            baseUrl = DeepSeekDefaults.BASE_URL,
            keyMasked = graph.keyVault.masked(DeepSeekDefaults.PROVIDER_ID).ifBlank { null },
            isVerified = graph.isTextProviderVerified(DeepSeekDefaults.PROVIDER_ID),
        ),
        TextModelRouter.TextModelEntry(
            modelId = AgnesProvider.TEXT_MODEL,
            label = "Agnes 文本 2.5 Flash",
            providerId = AgnesProvider.PROVIDER_ID,
            baseUrl = agnesBaseUrl(),
            keyMasked = graph.keyVault.masked(agnesConfigIdKey()).ifBlank { null },
            isVerified = graph.isTextProviderVerified(AgnesProvider.PROVIDER_ID),
        ),
        TextModelRouter.TextModelEntry(
            modelId = MiMoProvider.MODEL,
            label = "MiMo 文本 Pro",
            providerId = MiMoProvider.PROVIDER_ID,
            baseUrl = MiMoProvider.BASE_URL,
            keyMasked = graph.keyVault.masked(MiMoProvider.PROVIDER_ID).ifBlank { null },
            isVerified = graph.isTextProviderVerified(MiMoProvider.PROVIDER_ID),
        ),
    )

    /** 当前站点生效的 Agnes 基址（中国站且未自定义时用中国站根域） */
    private fun agnesBaseUrl(): String {
        val region = graph.providerPrefs.agnesRegion
        return if (region == AgnesRegion.CHINA && graph.providerPrefs.agnesBaseUrl == AgnesProvider.DEFAULT_BASE) {
            AgnesProvider.CHINA_BASE_URL
        } else {
            graph.providerPrefs.agnesBaseUrl
        }
    }

    /** 当前站点分池后的 Agnes Key 维度 */
    private fun agnesConfigIdKey(): String =
        agnesScopedConfigId(AgnesProvider.PROVIDER_ID, graph.providerPrefs.agnesRegion)

    override fun activeTextModelId(): String = graph.providerPrefs.activeTextModelId

    override suspend fun setActiveTextModel(modelId: String): Result<Unit> {
        val known = registeredTextModels().any { it.modelId == modelId }
        if (!known) return Result.failure(IllegalArgumentException("未知文本模型：$modelId"))
        graph.providerPrefs.activeTextModelId = modelId
        return Result.success(Unit)
    }

    override suspend fun validate(modelId: String): Result<ConnectionInfo> {
        val entry = registeredTextModels().firstOrNull { it.modelId == modelId }
            ?: return Result.failure(IllegalArgumentException("未知文本模型：$modelId"))
        val key = if (entry.providerId == AgnesProvider.PROVIDER_ID) {
            graph.keyVault.load(agnesConfigIdKey())
        } else {
            graph.keyVault.load(entry.providerId)
        }
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("${entry.label} 未配置 Key，请先填写"))
        }
        val provider = resolve(modelId)
        return provider.validateKey(key)
    }

    override suspend fun resolve(modelId: String): TextProvider = when {
        modelId == DeepSeekDefaults.MODEL || modelId.startsWith("deepseek") -> graph.openAiTextProvider
        modelId == MiMoProvider.MODEL || modelId.startsWith("mimo") -> graph.mimoProvider
        else -> graph.agnesProvider // agnes / agnes-2.5-flash → Agnes 文本通道
    }
}