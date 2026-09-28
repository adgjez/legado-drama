package com.legado.drama.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.engine.orchestrator.PipelineStage
import com.legado.drama.engine.orchestrator.ProgressEvent
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.DramaFilterChip
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.HeroButton
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import kotlinx.coroutines.launch

/**
 * 项目列表页（S1+S2，对齐源工程 ProjectsPage 结构）：
 * 模型配置卡（三通道 Key 状态）→ 新建项目卡（AI 一键成片 / 手动七阶段 双模式，
 * legado 建项目由 AI 流水线自动完成，故以一键成片为新建主路径）→ 项目列表（进入/删除）。
 */
@Composable
fun ProjectsPage(
    graph: AppGraph,
    onEnterProject: (projectId: String, projectName: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "AI短剧工厂", subtitle = "创建项目 · 导入剧本 · 进入制作")
        }
        item { HomeModelConfigCard(onOpenSettings) }
        item {
            NewProjectCard(graph)
        }
        if (projects.isEmpty()) {
            item {
                EmptyState(
                    icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有项目",
                    subtitle = "在「新建项目」卡里粘贴一段小说或剧本，AI 会帮你自动拆成资产、分镜、渲染并合成成片。",
                )
            }
        }
        items(projects, key = { it.projectId }) { p ->
            DramaCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                            .format(java.util.Date(p.createdAt))
                        Text("预算 ${p.budgetShots} 镜 · $dateStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onEnterProject(p.projectId, p.name) }) { Text("进入") }
                        OutlinedButton(onClick = { deleteTarget = p }) { Text("删除") }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目「${target.name}」？") },
            text = { Text("项目的资产、分镜与渲染记录将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.db.projectDao().delete(target.projectId)
                        snackbar.show("已删除项目「${target.name}」")
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/**
 * AI 一键成片入口卡（对齐源工程 ProjectsPage「新建项目」卡位）：
 * 双模式（AI 一键成片 / 手动七阶段评估，ProviderPrefs.lastMode 记忆），
 * 一键成片 = 粘贴剧本 + 版权确认 + 全托管五阶段流水线。
 */
@Composable
private fun NewProjectCard(graph: AppGraph) {
    var mode by remember(graph) {
        mutableStateOf(if (graph.providerPrefs.lastMode == ProviderPrefs.MODE_AI) "ai" else "manual")
    }
    DramaCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新建项目", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DramaFilterChip(
                    selected = mode == "ai",
                    onClick = {
                        mode = "ai"
                        graph.providerPrefs.lastMode = ProviderPrefs.MODE_AI
                    },
                    label = { Text("AI 一键成片") },
                )
                DramaFilterChip(
                    selected = mode == "manual",
                    onClick = {
                        mode = "manual"
                        graph.providerPrefs.lastMode = ProviderPrefs.MODE_MANUAL
                    },
                    label = { Text("手动七阶段") },
                )
            }
            when (mode) {
                "ai" -> AiPipelineBody(graph)
                else -> ManualPipelineBody(graph)
            }
        }
    }
}

/** AI 一键成片（T014 五阶段事件流） */
@Composable
private fun AiPipelineBody(graph: AppGraph) {
    val context = LocalContext.current
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()
    var script by remember { mutableStateOf("") }
    val events by graph.aiOrchestrator.events.collectAsState()
    var running by remember { mutableStateOf(false) }
    // Q4 版权确认框：未勾选不可一键成片
    var copyrightChecked by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = script,
        onValueChange = { script = it },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        label = { Text("粘贴剧本（≥100 字）") },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = copyrightChecked, onCheckedChange = { copyrightChecked = it })
        Text("我已确认拥有剧本著作权或合法改编授权（导入版权确认 Q4）", style = MaterialTheme.typography.bodySmall)
    }
    HeroButton(
        text = if (running) "流水线运行中…" else "一键成片",
        onClick = {
            scope.launch {
                running = true
                try {
                    graph.aiOrchestrator.run(script) { projectId, episodeId ->
                        graph.setActiveProject(projectId)
                        snackbar.show("已建项目并生成剧集 $episodeId")
                    }
                } catch (e: Exception) {
                    snackbar.show("启动失败：${e.message}")
                } finally {
                    running = false
                }
            }
        },
        enabled = script.replace(Regex("\\s"), "").length >= 100 && copyrightChecked && !running,
        modifier = Modifier.fillMaxWidth(),
    )
    if (events.isNotEmpty()) {
        Text(
            "流水线日志 · ${events.size} 条（长按任意行复制全文）",
            style = MaterialTheme.typography.titleMedium,
        )
        // 固定高度滚动的日志区（Event 已裁剪至 500 条内，Column 渲染即可）
        Column(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            events.reversed().forEach { e: ProgressEvent ->
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

/** 手动七阶段（S1 项目 → S7 成片 闸门评估） */
@Composable
private fun ManualPipelineBody(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalDramaSnackbar.current
    val stage by graph.pipelineOrchestrator.stage.collectAsState()
    var gateSummary by remember { mutableStateOf<String?>(null) }
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())

    Text("七阶段状态机", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in PipelineStage.entries) {
            val active = s == stage
            DramaFilterChip(
                selected = active,
                onClick = {},
                enabled = false,
                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
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
                snackbar.show("已评估 ${project.name} 闸门")
            }
        }
    }) { Text("评估当前项目闸门") }
    gateSummary?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "说明：S2 导入/S3 资产由 AI 流水线填充；S4 评审在资产页完成；S5 六铁律在渲染出队时复核；渲染在「渲染」页执行；S7 合并在「成片」页。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 首页「模型配置」入口卡（对齐源工程 HomeModelConfigCard）：
 * 三通道（文本/视频/图像）Key 状态一目了然，未配置的通道高亮提示，一键跳设置页补配。
 */
@Composable
private fun HomeModelConfigCard(onOpenSettings: () -> Unit) {
    val graph = com.legado.drama.AppGraph.get(
        androidx.compose.ui.platform.LocalContext.current,
    )
    val keyVault = graph.keyVault
    var hasText by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(false) }
    var hasImage by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Agnes 判据按当前站点分池读取（国际站 agnes / 中国站 agnes-cn）
        val agnesKeyId = com.legado.drama.provider.agnesScopedConfigId(
            com.legado.drama.provider.AgnesProvider.PROVIDER_ID,
            graph.providerPrefs.agnesRegion,
        )
        hasText = runCatching { keyVault.masked("deepseek").isNotEmpty() || keyVault.masked(agnesKeyId).isNotEmpty() }
            .getOrDefault(false)
        hasVideo = runCatching { keyVault.masked(agnesKeyId).isNotEmpty() }.getOrDefault(false)
        // 图像通道：优先独立图像 Key（agnes-image 分池），未配置时回退共享 Agnes Key（同 generateImage 取 Key 链）
        hasImage = runCatching { graph.hasImageKey() }.getOrDefault(false)
        checked = true
    }
    if (!checked) return

    val missing = listOf(!hasText to "文本", !hasVideo to "视频", !hasImage to "图像").filter { it.first }
    DramaCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (missing.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("全部就绪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text("待配置：${missing.joinToString("、") { it.second }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusDot("文本", hasText)
                StatusDot("视频", hasVideo)
                StatusDot("图像", hasImage)
            }
            Text("模型 Key 决定 AI 对话 / 出图 / 渲染能否跑通。首次使用先到这里补 Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (missing.isEmpty()) "管理模型 Key" else "去配置模型 Key")
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.padding(end = 2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}