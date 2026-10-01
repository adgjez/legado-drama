package com.legado.drama.provider

import com.legado.drama.engine.provider.VideoProvider
import com.legado.drama.engine.security.KeyVault
import com.legado.drama.provider.video.KlingProvider

/**
 * 视频通道供应商路由（P0-③）—— 对应文本通道的 TextModelRouter（对齐源工程 VideoProviderRouter）。
 *
 * 此前 AppGraph.renderQueue.videoProvider 硬指向 Agnes；现按「激活的视频供应商」动态构造对应适配器：
 * - agnes（默认）→ 复用已构建的 Agnes 实例（视频 Key 与文本 Key 同池，走 region 分池）
 * - kling → KlingProvider 专属适配器（Key 独立分池 configId = "kling-video"）
 *
 * 激活 id 由 AppGraph 注入的持久化闭包管理（ProviderPrefs，同步 SharedPreferences），
 * 以便 init 在非协程上下文完成；每个非 agnes 供应商 Key 独立分池（configId = "${id}-video"），
 * 不跨池回退。Key 明文通过 suspend 闭包按需读取（对齐本地 KeyVault 的 suspend 接口）。
 */
object VideoProviderRouter {

    /** agnes 视频 Key 维度（与文本共用 "agnes"/"agnes-cn" region 分池；源工程为独立 agnes-video 池，本地保持既有共用避免破坏存量配置） */
    private const val CONFIG_VIDEO = "agnes"

    lateinit var keyVault: KeyVault
    /** 当前 Agnes 服务站点（影响 agnes 通道与 Key 池） */
    lateinit var regionProvider: () -> AgnesRegion
    /** 返回已构建的 Agnes 实例（含自定义模型 override），供 agnes/custom 路由复用 */
    lateinit var agnesProviderProvider: () -> VideoProvider
    /** 激活 id 持久化读取（ProviderPrefs 同步读） */
    lateinit var activeIdProvider: () -> String
    /** 激活 id 持久化写入（ProviderPrefs 同步写） */
    lateinit var activeIdSetter: (String) -> Unit

    fun init(
        keyVault: KeyVault,
        regionProvider: () -> AgnesRegion,
        agnesProviderProvider: () -> VideoProvider,
        activeIdProvider: () -> String,
        activeIdSetter: (String) -> Unit,
    ) {
        this.keyVault = keyVault
        this.regionProvider = regionProvider
        this.agnesProviderProvider = agnesProviderProvider
        this.activeIdProvider = activeIdProvider
        this.activeIdSetter = activeIdSetter
    }

    /** 激活的供应商 id（默认 agnes） */
    fun activeVideoProviderId(): String =
        if (::activeIdProvider.isInitialized) activeIdProvider() else "agnes"

    /** 设置激活的视频供应商（保存 Key 或显式切换时调用，持久化到 ProviderPrefs） */
    fun setActive(id: String) {
        if (::activeIdSetter.isInitialized) activeIdSetter(id)
    }

    /** 供应商 → KeyVault configId（与设置页保存键一致） */
    fun configIdFor(providerId: String, region: AgnesRegion): String = when (providerId) {
        "agnes", "custom" -> agnesScopedConfigId(CONFIG_VIDEO, region)
        else -> "$providerId-video"
    }

    /** Key 是否已配置（按当前站点分池维度；同步读掩码，亚明文不落 UI） */
    fun isKeyConfigured(providerId: String): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = regionProvider()
        return keyVault.masked(configIdFor(providerId, region)).isNotEmpty()
    }

    /** 解析当前激活供应商的 VideoProvider 实例（Key 惰性按需读取） */
    fun resolve(): VideoProvider = resolveFor(activeVideoProviderId())

    /** 按指定供应商 id 解析（设置页测试连通/保存时用候选 Key；overrideKey 非空时优先） */
    fun resolveFor(
        providerId: String,
        overrideKey: String? = null,
    ): VideoProvider {
        val region = regionProvider()
        return build(providerId, region, overrideKey)
    }

    private fun build(id: String, region: AgnesRegion, overrideKey: String?): VideoProvider = when (id) {
        "agnes", "custom" -> agnesProviderProvider()
        "kling" -> KlingProvider(
            apiKeyProvider = { overrideKey ?: keyVault.load(configIdFor(id, region)) },
        )
        else -> agnesProviderProvider()
    }

    /** 当前激活供应商 Key 是否就绪（视频通道就绪闸门，供队列/设置页展示） */
    fun activeKeyReady(): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = regionProvider()
        return keyVault.masked(configIdFor(activeVideoProviderId(), region)).isNotEmpty()
    }
}