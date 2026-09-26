package com.legado.drama

import android.content.Context
import com.legado.drama.data.DramaDatabase
import com.legado.drama.data.RoomCheckpointStore
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.engine.gate.AssetGate
import com.legado.drama.engine.gate.DefaultAssetGate
import com.legado.drama.engine.gate.DefaultStoryboardGate
import com.legado.drama.engine.gate.StoryboardGate
import com.legado.drama.engine.queue.CheckpointStore
import com.legado.drama.engine.queue.DefaultRateGate
import com.legado.drama.engine.queue.RateGate
import com.legado.drama.engine.router.TextModelRouter
import com.legado.drama.engine.security.KeyVault
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.provider.OpenAiCompatTextProvider
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

    val db: DramaDatabase by lazy { DramaDatabase.build(appContext) }

    val rateGate: RateGate by lazy {
        DefaultRateGate(
            videoIntervalMs = providerPrefs.videoIntervalMs,
        )
    }

    val keyVault: KeyVault by lazy { AndroidKeyVault(appContext) }

    val checkpointStore: CheckpointStore by lazy { RoomCheckpointStore(db.renderTaskDao()) }

    val assetGate: AssetGate by lazy { DefaultAssetGate(g2Auditor = null) }

    val storyboardGate: StoryboardGate by lazy {
        DefaultStoryboardGate { assetIdsInProject() }
    }

    val agnesProvider: AgnesProvider by lazy {
        AgnesProvider(rateGate, keyVault, providerPrefs.agnesBaseUrl)
    }

    val openAiTextProvider: OpenAiCompatTextProvider by lazy {
        OpenAiCompatTextProvider(keyVault)
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

    /** 文本模型连通状态：配置表 is_verified 优先，无配置时按 Key 掩码兜底 */
    fun isTextProviderVerified(providerId: String): Boolean =
        configsCache.firstOrNull { it.channel == "text" && it.providerId == providerId }?.isVerified
            ?: keyVault.masked(providerId).isNotEmpty()

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