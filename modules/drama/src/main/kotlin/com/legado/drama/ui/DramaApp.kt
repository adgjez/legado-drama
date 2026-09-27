package com.legado.drama.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.ProviderPrefs
import com.legado.drama.engine.orchestrator.PipelineStage
import com.legado.drama.engine.orchestrator.ProgressEvent
import kotlinx.coroutines.launch

/** 底部导航 Tab */
private enum class DramaTab(val label: String) {
    HOME("首页"), QUEUE("渲染"), GALLERY("画廊"), FILMS("成片"), SETTINGS("设置"),
}

/**
 * drama 根界面：双模式首页 + 底部导航 + 全局 AI 悬浮球（HANDOVER C2）。
 * 模式记忆（ProviderPrefs.lastMode，T014 Q7）：进入即恢复上次模式。
 */
@Composable
fun DramaApp() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    var tab by remember { mutableStateOf(DramaTab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // C2：全局 AI 悬浮球 + 聊天面板
    var aiPanelOpen by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AiAssistantFloating(onClick = { aiPanelOpen = true })
        },
        bottomBar = {
            NavigationBar {
                for (t in DramaTab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                imageVector = when (t) {
                                    DramaTab.HOME -> Icons.Filled.Home
                                    DramaTab.QUEUE -> Icons.Filled.Movie
                                    DramaTab.GALLERY -> Icons.Filled.PhotoLibrary
                                    DramaTab.FILMS -> Icons.Filled.VideoLibrary
                                    DramaTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                DramaTab.HOME -> HomeScreen(graph, snackbar, scope)
                DramaTab.QUEUE -> RenderQueueScreen(graph)
                DramaTab.GALLERY -> GalleryScreen(graph, snackbar, scope)
                DramaTab.FILMS -> FilmsScreen(graph, snackbar, scope)
                DramaTab.SETTINGS -> SettingsScreen(graph, snackbar, scope)
            }
        }
    }
    // AI 聊天面板（全屏覆盖，不占 NavigationBar）
    if (aiPanelOpen) {
        AiAssistantPanel(onDismiss = { aiPanelOpen = false })
    }
}

/** 双模式首页：AI 一键成片 / 手动七阶段 */
@Composable
fun HomeScreen(
    graph: AppGraph,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var mode by remember(graph) {
        mutableStateOf(if (graph.providerPrefs.lastMode == ProviderPrefs.MODE_AI) "ai" else "manual")
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "ai", onClick = {
                mode = "ai"
                graph.providerPrefs.lastMode = ProviderPrefs.MODE_AI
            }, label = { Text("AI 一键成片") })
            FilterChip(selected = mode == "manual", onClick = {
                mode = "manual"
                graph.providerPrefs.lastMode = ProviderPrefs.MODE_MANUAL
            }, label = { Text("手动七阶段") })
        }
        Spacer(Modifier.height(12.dp))
        when (mode) {
            "ai" -> AiPipelineScreen(graph, snackbar, scope)
            else -> ManualPipelineScreen(graph, snackbar, scope)
        }
    }
}

/** AI 一键成片（T014 五阶段事件流） */
@Composable
private fun AiPipelineScreen(
    graph: AppGraph,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    var script by remember { mutableStateOf("") }
    val events by graph.aiOrchestrator.events.collectAsState()
    var running by remember { mutableStateOf(false) }
    // Q4 版权确认框：未勾选不可一键成片
    var copyrightChecked by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = script,
            onValueChange = { script = it },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("粘贴剧本（≥100 字）") },
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = copyrightChecked, onCheckedChange = { copyrightChecked = it })
            Text("我已确认拥有剧本著作权或合法改编授权（导入版权确认 Q4）", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            enabled = script.replace(Regex("\\s"), "").length >= 100 && copyrightChecked && !running,
            onClick = {
                scope.launch {
                    running = true
                    try {
                        graph.aiOrchestrator.run(script) { projectId, episodeId ->
                            graph.setActiveProject(projectId)
                            scope.launch { snackbar.showSnackbar("已建项目并生成剧集 $episodeId") }
                        }
                    } catch (e: Exception) {
                        snackbar.showSnackbar("启动失败：${e.message}")
                    } finally {
                        running = false
                    }
                }
            },
        ) { Text(if (running) "流水线运行中…" else "一键成片") }
        Spacer(Modifier.height(4.dp))
        Text(
            if (events.isEmpty()) "流水线日志（长按任意行复制全文；超过 500 条自动裁剪早期）"
            else "流水线日志 · ${events.size} 条",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(events.reversed()) { e: ProgressEvent ->
                ProgressRow(e, onLongClick = { copyLog(context, events) })
            }
        }
    }
}

/** 复制整条流水线日志到剪贴板（P2-1：长按复制去反馈） */
private fun copyLog(context: Context, events: List<ProgressEvent>) {
    val text = events.joinToString("\n") { e ->
        "+${e.elapsedMs / 1000}s [${e.stage.label}] ${if (e.level != ProgressEvent.Level.INFO) "${e.level} " else ""}${e.message}"
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("drama-pipeline-log", text))
    Toast.makeText(context, "流水线日志已复制（${events.size} 条）", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgressRow(e: ProgressEvent, onLongClick: () -> Unit) {
    val color = when (e.level) {
        ProgressEvent.Level.ERROR -> MaterialTheme.colorScheme.error
        ProgressEvent.Level.WARN -> MaterialTheme.colorScheme.tertiary
        ProgressEvent.Level.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "+${e.elapsedMs / 1000}s · ${e.stage.label} · ${e.message}",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
    )
}

/** 手动七阶段 */
@Composable
private fun ManualPipelineScreen(
    graph: AppGraph,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val stage by graph.pipelineOrchestrator.stage.collectAsState()
    var gateSummary by remember { mutableStateOf<String?>(null) }
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Text("七阶段状态机", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in PipelineStage.entries) {
                val active = s == stage
                FilterChip(
                    selected = active,
                    onClick = {},
                    enabled = false,
                    label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("① 阶段评估（S1 项目 → S7 成片）", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val project = projects.firstOrNull()
                        if (project == null) {
                            gateSummary = "暂无项目，请先使用 AI 一键成片"
                        } else {
                            graph.setActiveProject(project.projectId)
                            val report = graph.pipelineOrchestrator.evaluateGates(project.projectId)
                            gateSummary = buildString {
                                appendLine("资产生成: ${report.assetsGenerated}")
                                appendLine("评审通过: ${report.reviewPassed}")
                                appendLine("分镜六铁律: ${report.storyboardPassed}")
                                appendLine("Key 有效: ${report.keyValid}")
                                appendLine("预算: ${report.budgetOk}")
                                appendLine("可渲染: ${report.canRender}")
                            }
                        }
                    }
                }) { Text("评估当前项目闸门") }
                gateSummary?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("说明：S2 导入/S3 资产由 AI 流水线填充；S4 评审在画廊页完成；S5 六铁律在渲染出队时复核；渲染在「渲染」页执行；S7 合并在「成片」页。", style = MaterialTheme.typography.bodySmall)
    }
}