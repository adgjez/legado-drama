package com.legado.drama.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.legado.drama.AppGraph
import com.legado.drama.R
import com.legado.drama.ui.components.DramaSnackbarController
import com.legado.drama.ui.components.DramaSnackbarHost
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.LocalDramaSnackbar

/**
 * 主导航（对齐源工程 ai-drama-factory DramaApp 骨架，架构§4.1）：
 * 项目列表(S1) → 剧集(S2 子页) → 资产库(S3/S4) → 分镜(S5) → 渲染队列(S6) → 成片库(S7) → 设置(子页)。
 * 底栏常驻 5 项（项目/资产/分镜/渲染/成片）；剧集、设置为子页（TopAppBar 返回箭头）。
 * 「当前项目/剧集」经简单状态传递（不引 navigation 库，减依赖面）。
 *
 * 与源工程差异（引擎能力为约束）：legado 建项目/资产/分镜由 AI 一键成片自动完成，
 * 故项目页「新建项目」卡内嵌 AI 一键成片入口（含手动七阶段评估双模式），
 * 其余页面结构与源工程一致。
 */
enum class Page(@StringRes val labelRes: Int) {
    PROJECTS(R.string.page_projects), EPISODES(R.string.page_episodes), ASSETS(R.string.page_assets),
    STORYBOARD(R.string.page_storyboard), QUEUE(R.string.page_queue), LIBRARY(R.string.page_library),
    SETTINGS(R.string.page_settings);

    /** 是否在底栏常驻（收敛为 5 项；剧集/设置降级为子页） */
    val onBar: Boolean get() = this in listOf(PROJECTS, ASSETS, STORYBOARD, QUEUE, LIBRARY)
    /** 子页归属的主标签（底栏高亮映射） */
    val ownerMain: Page get() = when (this) {
        EPISODES -> PROJECTS
        SETTINGS -> lastMainCache
        else -> this
    }
}

/** 记录进入设置前的上一个主页面（设置子页返回用） */
private var lastMainCache: Page = Page.PROJECTS

/** 全局 UI 状态：当前选中项目/集（简化跨页上下文） */
class AppNavState {
    var currentProjectId by mutableStateOf<String?>(null)
    var currentProjectName by mutableStateOf<String?>(null)
    var currentEpisodeId by mutableStateOf("default")
}

/**
 * drama 根界面：7 页面导航 + 全局 AI 悬浮球 + 统一 Snackbar 通道。
 * 开屏动画由 DramaMainActivity 的 SplashGate 完成，本函数只负责主界面。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DramaApp() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val nav = remember { AppNavState() }
    var page by remember { mutableStateOf(Page.PROJECTS) }
    // 记录当前主标签（用于设置子页返回映射）
    if (page.onBar) lastMainCache = page
    // AI 悬浮球：贯穿整个 App
    var aiPanelOpen by remember { mutableStateOf(false) }

    // 统一瞬时反馈通道（对齐源工程 components/Snackbar.kt）
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, snackbarScope) {
        DramaSnackbarController(snackbarHostState, snackbarScope)
    }

    CompositionLocalProvider(LocalDramaSnackbar provides snackbarController) {
        Scaffold(
            snackbarHost = { DramaSnackbarHost(snackbarHostState) },
            topBar = {
                // 子页（剧集/设置）显示返回箭头；主页面显示设置齿轮
                TopAppBar(
                    title = { Text(stringResource(page.labelRes), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        if (!page.onBar) IconButton(onClick = {
                            page = if (page == Page.SETTINGS) lastMainCache else page.ownerMain
                        }) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back)) }
                    },
                    actions = {
                        if (page.onBar) IconButton(onClick = {
                            lastMainCache = page
                            page = Page.SETTINGS
                        }) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    for (p in Page.entries.filter { it.onBar }) {
                        val label = stringResource(p.labelRes)
                        NavigationBarItem(
                            selected = page == p,
                            onClick = { page = p },
                            icon = { Icon(pageIcon(p), contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                AiAssistantFloating(onClick = { aiPanelOpen = true })
            },
        ) { padding ->
            androidx.compose.foundation.layout.Box(
                Modifier.padding(padding),
            ) {
                when (page) {
                    Page.PROJECTS -> ProjectsPage(
                        graph = graph,
                        onEnterProject = { id, name ->
                            nav.currentProjectId = id
                            nav.currentProjectName = name
                            page = Page.EPISODES
                        },
                        onOpenSettings = {
                            lastMainCache = Page.PROJECTS
                            page = Page.SETTINGS
                        },
                    )
                    Page.EPISODES -> nav.currentProjectId?.let { pid ->
                        EpisodePage(
                            graph = graph,
                            projectId = pid,
                            projectName = nav.currentProjectName,
                            onOpenEpisode = { epId ->
                                nav.currentEpisodeId = epId
                                page = Page.ASSETS
                            },
                        )
                    } ?: EmptyState(title = stringResource(R.string.app_project_gone))
                    Page.ASSETS -> AssetsPage(
                        graph = graph,
                        projectId = nav.currentProjectId ?: "",
                        onContinue = { page = Page.QUEUE },
                    )
                    Page.STORYBOARD -> StoryboardPage(
                        graph = graph,
                        episodeId = nav.currentEpisodeId,
                    )
                    Page.QUEUE -> QueuePage(graph = graph)
                    Page.LIBRARY -> LibraryPage(graph = graph)
                    Page.SETTINGS -> SettingsPage(graph = graph)
                }
                // AI 悬浮球放外层 Box：与内容区重叠，真正"悬浮"覆盖，不参与内容测量
            }
        }
    }
    // AI 聊天面板（全屏覆盖，不占 NavigationBar）
    if (aiPanelOpen) {
        AiAssistantPanel(graph = graph, onDismiss = { aiPanelOpen = false })
    }
}

/** 底栏语义图标（与源工程 ic_folder/ic_image/ic_list/ic_cpu/ic_movie 同义的 Material 图标） */
private fun pageIcon(p: Page): ImageVector = when (p) {
    Page.PROJECTS -> Icons.Filled.Folder
    Page.ASSETS -> Icons.Filled.Image
    Page.STORYBOARD -> Icons.Filled.List
    Page.QUEUE -> Icons.Filled.Movie
    Page.LIBRARY -> Icons.Filled.VideoLibrary
    Page.EPISODES -> Icons.Filled.List
    Page.SETTINGS -> Icons.Filled.Settings
}