package com.legado.drama

import android.content.Context
import com.legado.drama.data.DramaDatabase
import com.legado.drama.data.RoomCheckpointStore
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.engine.gate.AssetGate
import com.legado.drama.engine.gate.DefaultAssetGate
import com.legado.drama.engine.gate.DefaultEraDetector
import com.legado.drama.engine.gate.DefaultFidelityGate
import com.legado.drama.engine.gate.DefaultG2Auditor
import com.legado.drama.engine.gate.DefaultStoryboardGate
import com.legado.drama.engine.gate.EraDetector
import com.legado.drama.engine.gate.FidelityGate
import com.legado.drama.engine.gate.StoryboardGate
import com.legado.drama.engine.queue.CheckpointStore
import com.legado.drama.engine.queue.DefaultRateGate
import com.legado.drama.engine.queue.RateGate
import com.legado.drama.engine.router.TextModelRouter
import com.legado.drama.engine.security.KeyVault
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.provider.AgnesRegion
import com.legado.drama.provider.MiMoProvider
import com.legado.drama.provider.OpenAiCompatTextProvider
import com.legado.drama.provider.agnesScopedConfigId
import com.legado.drama.router.TextModelRouterImpl
import com.legado.drama.security.AndroidKeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局单例装配（架构文档 §1.1 MVVM + 共享单例；T014 AppGraph）：
 * 手动构造（不引入 Hilt 以最低侵入集成 Legado 阅读器），
 * DramaMainActivity / ForegroundService / AiOrchestrator 共享同一实例。
 */
class AppGraph private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 全局崩溃兜底（HANDOVER C4/CrashLog；P2-9 隐私修正：不写公共下载目录，
     * 只写应用私有 files/crash/last_crash.txt，避免崩溃栈含敏感信息外泄）。
     */
    init {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = java.io.File(context.filesDir, "crash").apply { mkdirs() }
                val out = java.io.File(dir, "last_crash.txt")
                out.writeText(
                    buildString {
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine("thread=${thread.name}")
                        appendLine(throwable.stackTraceToString())
                    },
                )
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    val db: DramaDatabase by lazy { DramaDatabase.build(appContext) }

    val rateGate: RateGate by lazy {
        DefaultRateGate(
            videoIntervalMs = providerPrefs.videoIntervalMs,
        )
    }

    val keyVault: KeyVault by lazy { AndroidKeyVault(appContext) }

    val checkpointStore: CheckpointStore by lazy { RoomCheckpointStore(db.renderTaskDao()) }

    val assetGate: AssetGate by lazy { DefaultAssetGate(g2Auditor = g2Auditor) }

    /** G2 多模态质量审计（P0-① 接线：Agnes 文本通道带图 chat，defects 非空直接拒，重试 ≤3） */
    val g2Auditor: DefaultG2Auditor by lazy {
        DefaultG2Auditor(
            textProvider = agnesProvider,
        )
    }

    val storyboardGate: StoryboardGate by lazy {
        DefaultStoryboardGate { assetIdsInProject() }
    }

    /** 时代红线检测（P0-FIX F3：AI 模式自动断代，写入项目 style_preset） */
    val eraDetector: EraDetector by lazy { DefaultEraDetector() }

    /** 提交前忠实性闸门（HANDOVER §3.1 FidelityGate：单镜出队前复核台词/资产） */
    val fidelityGate: FidelityGate by lazy { DefaultFidelityGate() }

    val agnesProvider: AgnesProvider by lazy {
        AgnesProvider(rateGate, keyVault, providerPrefs.agnesBaseUrl, providerPrefs.agnesRegion)
    }

    val openAiTextProvider: OpenAiCompatTextProvider by lazy {
        OpenAiCompatTextProvider(keyVault)
    }

    /** 小米 MiMo 文本通道（P0-②：tp-/sk- 前缀自动选站，mimo-v2.6-pro） */
    val mimoProvider: MiMoProvider by lazy {
        MiMoProvider(keyVault)
    }

    /** 渲染队列（单例，供 UI/Service/AiOrchestrator 共享） */
    val renderQueue: com.legado.drama.engine.queue.RenderQueue by lazy {
        com.legado.drama.orchestration.DefaultRenderQueue(
            videoProvider = agnesProvider,
            checkpoint = checkpointStore,
            budget = budgetGuard,
            shotDao = db.shotDao(),
            episodeDao = db.episodeDao(),
            renderTaskDao = db.renderTaskDao(),
            scope = scope,
            filesDirProvider = { appContext.filesDir },
            assetUriResolver = { assetId -> assetUriCache[assetId] },
            fidelityGate = fidelityGate,
            assetIdsInProject = { assetIdsInProject() },
        ).also { q ->
            q.onShotCompleted = { /* 通知栏由 Service 观察快照，无需额外回调 */ }
            com.legado.drama.orchestration.StoryboardChecker.provider =
                { script, shots -> storyboardGate.check(script, shots) }
        }
    }

    /** 预算闸门（条数型，Q2）：由编排器按项目注入 */
    val budgetGuard: com.legado.drama.engine.gate.BudgetGuard by lazy {
        com.legado.drama.engine.gate.DefaultBudgetGuard(
            com.legado.drama.engine.gate.BudgetUsage(
                projectId = "", usedShots = 0, limitShots = 50,
            ),
        )
    }

    /** 七阶段编排器 */
    val pipelineOrchestrator: com.legado.drama.engine.orchestrator.PipelineOrchestrator by lazy {
        com.legado.drama.orchestration.DefaultPipelineOrchestrator(this, db.shotDao(), db.episodeDao())
    }

    /** AI 全托管五阶段编排（T014） */
    val aiOrchestrator: com.legado.drama.engine.orchestrator.AiOrchestrator by lazy {
        com.legado.drama.orchestration.DefaultAiOrchestrator(this) { episodeId ->
            renderQueue.enqueueEpisode(episodeId)
            com.legado.drama.service.RenderForegroundService.start(appContext, episodeId)
        }
    }

    /** 端上成片合成（Media3 降级实现；ffmpeg-kit 上游归档不可获取时启用） */
    val movieAssembler: com.legado.drama.engine.assemble.MovieAssembler by lazy {
        com.legado.drama.assemble.Media3MovieAssembler(appContext)
    }

    /** 设置项快捷读取（架构文档 §4.3 / 版本目录），非法值兜底 */
    val providerPrefs: ProviderPrefs by lazy { ProviderPrefs(appContext) }

    /** 文本模型路由（T014 §2.3 Q4：多模型并存、随时互切） */
    val textRouter: TextModelRouter by lazy { TextModelRouterImpl(this) }

    /** provider_configs 缓存（registeredTextModels 同步读；设置页保存后 refreshConfigs 刷新） */
    @Volatile
    private var configsCache: List<ProviderConfigEntity> = emptyList()

    /** 刷新配置缓存（设置页保存/验证后调用） */
    suspend fun refreshConfigs() {
        configsCache = db.providerConfigDao().listByChannels(listOf("video", "text", "image"))
    }

    /** 文本模型连通状态：配置表 is_verified 优先，无配置时按 Key 掩码兜底（Agnes 按站点分池） */
    fun isTextProviderVerified(providerId: String): Boolean =
        configsCache.firstOrNull { it.channel == "text" && it.providerId == providerId }?.isVerified
            ?: keyVault.masked(agnesScopedConfigId(providerId, providerPrefs.agnesRegion)).isNotEmpty()

    /**
     * 切换 Agnes 站点（对齐源工程 AppGraph.applyAgnesRegion/rebuilAgnes）：
     * 更新持久化 region + 热更新 provider 实例的 region（Key 维度与基址随即分池生效），
     * 无需重建 provider（renderQueue 等持有同一实例引用）。
     */
    fun applyAgnesRegion(region: AgnesRegion) {
        providerPrefs.agnesRegion = region
        agnesProvider.region = region
    }

    /** 当前站点分池后的独立图像 Key 维度（国际站 agnes-image / 中国站 agnes-image-cn） */
    fun agnesImageKeyId(): String =
        agnesScopedConfigId(AgnesProvider.CONFIG_IMAGE, providerPrefs.agnesRegion)

    /**
     * 图像通道是否就绪（对齐源工程 AppGraph.hasImageKey 同源语义）：
     * 优先独立图像 Key（agnes-image 分池），未配置时回退共享 Agnes Key（agnesis 分池），
     * 与 AgnesProvider.generateImage 的运行时取 Key 链一致。
     */
    fun hasImageKey(): Boolean {
        if (keyVault.masked(agnesImageKeyId()).isNotEmpty()) return true
        return keyVault.masked(agnesScopedConfigId(AgnesProvider.PROVIDER_ID, providerPrefs.agnesRegion)).isNotEmpty()
    }

    /** 当前剧集所属项目的资产 ID 白名单（六铁律·资产真实绑定） */
    private var activeProjectId: String = ""
    /** 资产白名单缓存：StoryboardGate 构造签名是同步 lambda，故用缓存桥接 Room 挂起查询 */
    @Volatile
    private var assetIdsCache: Set<String> = emptySet()
    /** 资产 id → 渲染用 URI（keyframes 首尾帧取真实文件/远程 data uri，架构文档 keyframes 双帧模式） */
    @Volatile
    private var assetUriCache: Map<String, String> = emptyMap()

    fun setActiveProject(projectId: String) {
        activeProjectId = projectId
        scope.launch {
            if (projectId.isEmpty()) {
                assetIdsCache = emptySet()
                assetUriCache = emptyMap()
            } else {
                val assets = db.assetDao().listByProject(projectId)
                assetIdsCache = assets.map { it.assetId }.toSet()
                assetUriCache = assets.mapNotNull { a -> a.fileUri?.let { a.assetId to it } }.toMap()
            }
        }
    }

    private fun assetIdsInProject(): Set<String> = assetIdsCache

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
        }

        /** 测试注入 */
        fun setForTest(graph: AppGraph?) {
            instance = graph
        }
    }
}