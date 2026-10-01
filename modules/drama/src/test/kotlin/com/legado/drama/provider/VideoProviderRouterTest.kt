package com.legado.drama.provider

import com.legado.drama.engine.provider.VideoProvider
import com.legado.drama.engine.security.KeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频供应商路由纯逻辑单测（P0-③）—— 不触网。
 * 覆盖：激活 id 持久化挂载、configId 分池（agnes region / kling 独立池）、
 * resolveFor 按供应商构造、Key 就绪判定。
 */
class VideoProviderRouterTest {

    /** 测试用内存 KeyVault（伪实现，不落盘） */
    private class FakeKeyVault : KeyVault {
        private val store = mutableMapOf<String, String>()
        override suspend fun save(configId: String, providerId: String, plainKey: String) {
            store[configId] = plainKey
        }

        override suspend fun load(configId: String): String = store[configId] ?: ""
        override fun masked(configId: String): String {
            val raw = store[configId] ?: ""
            if (raw.isEmpty()) return ""
            return if (raw.length <= 6) "*".repeat(raw.length)
            else raw.take(3) + "***" + raw.takeLast(3)
        }

        override suspend fun delete(configId: String) {
            store.remove(configId)
        }
    }

    private val agnesRegion = AgnesRegion.INTERNATIONAL
    private val chinaRegion = AgnesRegion.CHINA

    private lateinit var activeId: String

    private fun initRouter(
        keyVault: KeyVault = FakeKeyVault(),
        region: AgnesRegion = agnesRegion,
        agnesProvider: VideoProvider = fakeProvider("agnes"),
    ) {
        activeId = "agnes"
        VideoProviderRouter.init(
            keyVault = keyVault,
            regionProvider = { region },
            agnesProviderProvider = { agnesProvider },
            activeIdProvider = { activeId },
            activeIdSetter = { activeId = it },
        )
    }

    private fun fakeProvider(id: String) = object : VideoProvider {
        override val id: String = id
        override suspend fun validateKey(key: String) =
            Result.success(com.legado.drama.engine.provider.ConnectionInfo(ok = true, message = "ok", model = id))

        override fun listModels() = emptyList<com.legado.drama.engine.provider.ModelSpec>()
        override suspend fun submitVideo(req: com.legado.drama.engine.provider.VideoSubmitRequest) = id
        override suspend fun pollResult(providerTaskId: String) =
            com.legado.drama.engine.provider.PollResult.InProgress(null)
    }

    // ── 激活 id 持久化挂载：默认 agnes，setActive 写入注入的 setter ──
    @Test
    fun `active provider defaults to agnes`() {
        initRouter()
        assertEquals("agnes", VideoProviderRouter.activeVideoProviderId())
    }

    @Test
    fun `setActive persists via injected setter`() {
        initRouter()
        VideoProviderRouter.setActive("kling")
        assertEquals("kling", VideoProviderRouter.activeVideoProviderId())
        assertEquals("kling", activeId)
    }

    // ── configId 分池：agnes 国际/中国、kling 独立池 ──
    @Test
    fun `agnes config id uses region scoped pool`() {
        assertEquals("agnes", VideoProviderRouter.configIdFor("agnes", AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-cn", VideoProviderRouter.configIdFor("agnes", AgnesRegion.CHINA))
    }

    @Test
    fun `custom config id uses region scoped pool`() {
        assertEquals("agnes", VideoProviderRouter.configIdFor("custom", AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-cn", VideoProviderRouter.configIdFor("custom", AgnesRegion.CHINA))
    }

    @Test
    fun `non agnes config id uses id video pool`() {
        assertEquals("kling-video", VideoProviderRouter.configIdFor("kling", AgnesRegion.INTERNATIONAL))
        assertEquals("kling-video", VideoProviderRouter.configIdFor("kling", AgnesRegion.CHINA))
    }

    // ── resolveFor：agnes → agnes provider；kling → KlingProvider ──
    @Test
    fun `resolve agnes returns agnes provider`() {
        val agnes = fakeProvider("agnes")
        initRouter(agnesProvider = agnes)
        assertEquals(agnes, VideoProviderRouter.resolveFor("agnes"))
    }

    @Test
    fun `resolve kling returns kling provider with id`() {
        initRouter()
        val p = VideoProviderRouter.resolveFor("kling", overrideKey = "sk-kling")
        assertEquals("kling", p.id)
    }

    // ── Key 就绪判定 ──
    @Test
    fun `active key ready false when not configured`() {
        initRouter()
        assertFalse(VideoProviderRouter.activeKeyReady())
    }

    @Test
    fun `kling key ready when saved under kling video pool`() = kotlinx.coroutines.test.runTest {
        val vault = FakeKeyVault()
        initRouter(keyVault = vault, region = chinaRegion)
        vault.save(VideoProviderRouter.configIdFor("kling", chinaRegion), "kling", "sk-1234567890")
        VideoProviderRouter.setActive("kling")
        assertTrue(VideoProviderRouter.activeKeyReady())
    }

    @Test
    fun `agnes key scoped by region no cross pool fallback`() = kotlinx.coroutines.test.runTest {
        val vault = FakeKeyVault()
        // 仅配置中国站 Key，国际站激活时不应就绪（不跨池回退）
        initRouter(keyVault = vault, region = chinaRegion)
        vault.save("agnes-cn", "agnes", "sk-cn-1234567890")
        VideoProviderRouter.setActive("agnes")
        assertTrue(VideoProviderRouter.activeKeyReady())

        // 切到国际站（regionProvider 指向国际站），中国站 Key 不应复用
        initRouter(keyVault = vault, region = agnesRegion)
        VideoProviderRouter.setActive("agnes")
        assertFalse(VideoProviderRouter.activeKeyReady())
    }
}