package com.legado.drama.ui

import android.content.Intent
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.legado.drama.AppGraph
import com.legado.drama.ProviderPrefs
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.engine.assemble.MovieAssembler
import com.legado.drama.engine.queue.QueueSnapshot
import com.legado.drama.engine.queue.ShotState
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.provider.AgnesRegion
import com.legado.drama.provider.agnesScopedConfigId
import com.legado.drama.ui.components.DramaFilterChip
import com.legado.drama.service.RenderForegroundService
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import com.legado.drama.ui.components.PrimaryButton
import com.legado.drama.ui.components.statusErr
import com.legado.drama.ui.components.statusInfo
import com.legado.drama.ui.components.statusOk
import kotlinx.coroutines.launch
import java.io.File

/**
 * 渲染队列页（S6，对齐源工程 QueuePage 结构）：
 * PageHeader + 总进度卡（完成/失败/排队 + 进度条 + 暂停原因）+ 单镜任务 DramaCard 列表。
 */
@Composable
fun QueuePage(graph: AppGraph) {
    val snapshot by graph.renderQueue.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalDramaSnackbar.current

    // 快照带 episodeId 时顺带读单镜
    val episodeId = snapshot.episodeId
    if (episodeId != null) {
        val flow = remember(episodeId) { graph.db.renderTaskDao().observeByEpisode(episodeId) }
        val list by flow.collectAsState(initial = emptyList())
        QueueBody(graph, snapshot, list, scope)
        return
    }
    // 无快照：取最新剧集观察
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val firstProject = projects.firstOrNull()
    if (firstProject != null) {
        val eps by graph.db.episodeDao().observeByProject(firstProject.projectId).collectAsState(initial = emptyList())
        val firstEp = eps.firstOrNull()
        if (firstEp != null) {
            val flow = remember(firstEp.episodeId) { graph.db.renderTaskDao().observeByEpisode(firstEp.episodeId) }
            val list by flow.collectAsState(initial = emptyList())
            QueueBody(graph, snapshot, list, scope)
            return
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        PageHeader(title = "渲染队列", subtitle = "镜头状态机实时刷新 · 可暂停/恢复")
        EmptyState(
            icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
            title = "暂无剧集",
            subtitle = "暂无渲染任务。请先在「项目」页用 AI 一键成片生成剧集。",
        )
    }
}

@Composable
private fun QueueBody(
    graph: AppGraph,
    snapshot: QueueSnapshot,
    tasks: List<RenderTaskEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val snackbar = LocalDramaSnackbar.current
    var budgetConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "渲染队列", subtitle = "镜头状态机实时刷新 · 可暂停/恢复")

        // ---- 总进度卡（对齐源工程 QueuePage 总进度 DramaCard） ----
        DramaCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val epDisplay = snapshot.episodeId?.substringAfterLast("_")?.takeIf { it.startsWith("ep", ignoreCase = true) }
                    ?: snapshot.episodeId
                if (snapshot.total > 0) {
                    Text(
                        "第${epDisplay}集 · ${snapshot.completed}/${snapshot.total} 镜完成",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (snapshot.total > 0) snapshot.completed / snapshot.total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "完成 ${snapshot.completed} · 失败 ${snapshot.failed} · 排队 ${snapshot.pending} · 处理中 ${snapshot.submittedInFlight}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    snapshot.pausedReason?.let {
                        Text("暂停原因：${pauseLabel(it)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    snapshot.lastMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("队列空闲", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (snapshot.pausedReason != null) "恢复渲染" else "开始渲染",
                        onClick = {
                            val reason = snapshot.pausedReason
                            if (reason == null) {
                                val ep = snapshot.episodeId
                                if (ep != null) {
                                    RenderForegroundService.start(graph.appContext, ep)
                                    snackbar.show("渲染服务已启动")
                                }
                            } else if (reason == "budget") {
                                // 预算达上限：必须显式确认放行（引擎 resume(confirmedByUser=true)）
                                budgetConfirm = true
                            } else {
                                graph.scope.launch {
                                    graph.renderQueue.resume(confirmedByUser = false)
                                    snackbar.show("已恢复渲染")
                                }
                            }
                        },
                        enabled = snapshot.episodeId != null,
                    )
                    OutlinedButton(onClick = {
                        graph.scope.launch { graph.renderQueue.pause("user") }
                        snackbar.show("已暂停渲染")
                    }, enabled = snapshot.total > 0) { Text("暂停") }
                }
            }
        }

        // ---- 预算达标上限确认弹窗（对齐源工程预算确认放行位语义） ----
        if (budgetConfirm) {
            AlertDialog(
                onDismissRequest = { budgetConfirm = false },
                title = { Text("预算已达上限") },
                text = { Text("已暂停：预算达上限，等待确认。继续渲染将超出预设上限并产生额外费用。\n\n确认继续吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        graph.scope.launch {
                            graph.renderQueue.resume(confirmedByUser = true)
                            snackbar.show("已确认超限放行，恢复渲染")
                        }
                        budgetConfirm = false
                    }) { Text("继续渲染（超限放行）") }
                },
                dismissButton = {
                    TextButton(onClick = { budgetConfirm = false }) { Text("暂不渲染") }
                },
            )
        }

        if (tasks.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "暂无渲染任务",
                subtitle = "episode=${snapshot.episodeId ?: "-"} · 渲染任务入队后会在这里实时刷新",
            )
        } else {
            // ---- 单镜任务列表（对齐源工程 QueuePage 镜状态卡） ----
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.shotId }) { t ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("镜 ${t.shotId.takeLast(4)}", style = MaterialTheme.typography.titleSmall)
                                Text("状态 ${shotStateLabel(t.state)}", color = shotStateColor(t.state), style = MaterialTheme.typography.bodySmall)
                            }
                            t.failReason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            t.blockedReason?.let {
                                Text("阻塞：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            // 单镜取消（对齐源工程 QueuePage 取消按钮，cancelShot 终止该镜）
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    graph.renderQueue.cancelShot(t.shotId)
                                    snackbar.show("镜 ${t.shotId.takeLast(4)} 已取消")
                                }) { Text("取消") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pauseLabel(reason: String): String = when (reason) {
    "budget" -> "预算达上限，等待确认"
    "network" -> "网络异常"
    "auth" -> "API Key 失效，请到设置页更新"
    "review" -> "资产评审未通过"
    "noshots" -> "本集没有分镜"
    else -> reason
}

private fun shotStateLabel(state: String): String = when (state) {
    ShotState.PENDING.name -> "排队"
    ShotState.SUBMITTED.name -> "渲染中"
    ShotState.COMPLETED.name -> "已完成"
    ShotState.FAILED.name -> "失败"
    ShotState.BLOCKED.name -> "阻塞"
    else -> state
}

@Composable
private fun shotStateColor(state: String) = when (state) {
    ShotState.COMPLETED.name -> MaterialTheme.colorScheme.primary
    ShotState.FAILED.name, ShotState.BLOCKED.name -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

/**
 * 成片库页（S7，对齐源工程 LibraryPage 结构）：
 * PageHeader + 合成整集卡（仅当全部单镜 COMPLETED 才可合成）+ 成片 DramaCard 列表（播放/分享/删除）。
 */
@Composable
fun LibraryPage(graph: AppGraph) {
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()
    val films by graph.db.finishedFilmDao().observeAll().collectAsState(initial = emptyList())
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull()
    var deleteTarget by remember { mutableStateOf<FinishedFilmEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "成片库", subtitle = "已合成剧集 · 可播放与分享")

        if (project != null) {
            val eps by graph.db.episodeDao().observeByProject(project.projectId).collectAsState(initial = emptyList())
            val ep = eps.firstOrNull()
            if (ep != null) {
                val renderFlow = remember(ep.episodeId) { graph.db.renderTaskDao().observeByEpisode(ep.episodeId) }
                val renderTasks by renderFlow.collectAsState(initial = emptyList())
                val allCompleted = renderTasks.isNotEmpty() &&
                    renderTasks.all { it.state == ShotState.COMPLETED.name && !it.localFileUri.isNullOrBlank() }
                DramaCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前剧集合成", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第${ep.epNo}集（${eps.size} 集 · 已渲染 ${renderTasks.count { it.state == ShotState.COMPLETED.name }}/${renderTasks.size} 镜）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        PrimaryButton(
                            text = if (allCompleted) "合成整集成片" else "合成（需全部单镜渲染完成）",
                            onClick = {
                                scope.launch {
                                    val clips = renderTasks
                                        .filter { it.state == ShotState.COMPLETED.name && !it.localFileUri.isNullOrBlank() }
                                        .mapNotNull { File(it.localFileUri!!).takeIf { f -> f.exists() && f.length() > 0 } }
                                    if (clips.isEmpty()) {
                                        snackbar.show("没有已落盘的单镜片段，请先完成渲染")
                                        return@launch
                                    }
                                    val output = File(graph.appContext.filesDir, "movies/${ep.episodeId}.mp4").apply { parentFile?.mkdirs() }
                                    when (val r = graph.movieAssembler.assemble(clips, output)) {
                                        is MovieAssembler.AssembleResult.Success -> {
                                            graph.db.finishedFilmDao().upsert(
                                                FinishedFilmEntity(
                                                    filmId = ep.episodeId,
                                                    episodeId = ep.episodeId,
                                                    projectId = project.projectId,
                                                    fileUri = r.output.absolutePath,
                                                    fileSize = r.output.length(),
                                                    durationSeconds = r.durationSeconds,
                                                    strategy = r.strategy.name,
                                                    assembledAt = System.currentTimeMillis(),
                                                    updatedAt = System.currentTimeMillis(),
                                                ),
                                            )
                                            snackbar.show("成片完成：${r.strategy.label}")
                                        }
                                        is MovieAssembler.AssembleResult.Segmented -> {
                                            snackbar.show("分段导出 ${r.parts.size} 段（尚未合并）")
                                        }
                                        is MovieAssembler.AssembleResult.Failure -> {
                                            snackbar.show("合成失败：${r.message}")
                                        }
                                    }
                                }
                            },
                            enabled = allCompleted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (films.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "还没有成片",
                subtitle = "渲染完成后在「成片」页合成整集，成片将出现在这里。",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(films, key = { it.filmId }) { f ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("第${f.filmId.substringAfterLast("_", f.filmId)}集 · ${f.strategy}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "时长 ${"%.1f".format(f.durationSeconds)}s · ${f.fileSize / 1024}KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Text(
                                java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.assembledAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { playFilm(graph, f) }) { Text("播放") }
                                OutlinedButton(onClick = { shareFilm(graph, f) }) { Text("分享") }
                                OutlinedButton(onClick = { deleteTarget = f }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除成片") },
            text = { Text("确定删除 ${target.filmId.take(8)}… 吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.db.finishedFilmDao().delete(target.filmId)
                        File(target.fileUri).takeIf { it.exists() }?.delete()
                        snackbar.show("成片已删除")
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 播放成片：FileProvider + ACTION_VIEW（外部播放器） */
private fun playFilm(graph: AppGraph, f: FinishedFilmEntity) {
    val file = File(f.fileUri)
    if (!file.exists() || file.length() <= 0) return
    val context = graph.appContext
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileProvider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

/** 分享成片：FileProvider + ACTION_SEND */
private fun shareFilm(graph: AppGraph, f: FinishedFilmEntity) {
    val file = File(f.fileUri)
    if (!file.exists() || file.length() <= 0) return
    val context = graph.appContext
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileProvider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享成片"))
}

/**
 * 设置页（子页，对齐源工程 SettingsPage 结构）：
 * PageHeader + 分区 DramaCard（文本模型区块 / 视频通道区块）。
 * Key 录入通过 Keystore 加密（AndroidKeyVault），不落明文。
 */
@Composable
fun SettingsPage(graph: AppGraph) {
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()
    var agnesKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(graph.providerPrefs.videoIntervalMs.toString()) }
    var baseUrl by remember { mutableStateOf(graph.providerPrefs.agnesBaseUrl) }
    var agnesRegion by remember { mutableStateOf(graph.providerPrefs.agnesRegion) }
    val configs by graph.db.providerConfigDao().observeAll().collectAsState(initial = emptyList())

    /** 切换站点：持久化 + 热更新 provider + 默认基址联动（用户自定义基址不改写） */
    fun switchAgnesRegion(target: AgnesRegion) {
        if (target == agnesRegion) return
        val custom = baseUrl != ProviderPrefs.DEFAULT_AGNES_BASE && baseUrl != AgnesProvider.CHINA_BASE_URL
        agnesRegion = target
        graph.providerPrefs.agnesRegion = target
        graph.applyAgnesRegion(target)
        if (!custom) {
            baseUrl = if (target == AgnesRegion.CHINA) AgnesProvider.CHINA_BASE_URL else ProviderPrefs.DEFAULT_AGNES_BASE
        }
        snackbar.show("Agnes 站点已切换：${if (target == AgnesRegion.CHINA) "中国站" else "国际站"}（两站 Key 独立）")
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            PageHeader(title = "设置", subtitle = "模型供应商 · Key 加密存储 · 渲染参数")

            // ── Agnes 站点分池（对齐源工程 AgnesRegion 卡片：两站 Key 独立） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Agnes 站点", style = MaterialTheme.typography.titleMedium)
                        Text("当前：${if (agnesRegion == AgnesRegion.CHINA) "中国站" else "国际站"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        "国际站（apihub.agnes-ai.com）与中国站（api.agnes-ai.cn）Key 相互独立、分开保存；切换站点后请确认该站已填写对应 Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DramaFilterChip(
                            selected = agnesRegion == AgnesRegion.INTERNATIONAL,
                            onClick = { switchAgnesRegion(AgnesRegion.INTERNATIONAL) },
                            label = { Text("国际站") },
                        )
                        DramaFilterChip(
                            selected = agnesRegion == AgnesRegion.CHINA,
                            onClick = { switchAgnesRegion(AgnesRegion.CHINA) },
                            label = { Text("中国站") },
                        )
                    }
                }
            }

            // ── 文本模型区块（T014 §2.3 Q4：DeepSeek/Agnes 双模型、随时互切） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("文本模型（剧本/分镜大脑）", style = MaterialTheme.typography.titleMedium)
                        Text("生效：${graph.textRouter.activeTextModelId()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    val registered = remember(agnesRegion) { graph.textRouter.registeredTextModels() }
                    var activeModelId by remember { mutableStateOf(graph.textRouter.activeTextModelId()) }
                    registered.forEach { model ->
                        val selected = model.modelId == activeModelId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, onClick = {
                                    activeModelId = model.modelId
                                    scope.launch {
                                        graph.textRouter.setActiveTextModel(model.modelId)
                                        snackbar.show("文本模型已切换：${model.label}")
                                    }
                                }),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(model.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        append("模型 ${model.modelId}")
                                        model.keyMasked?.let { append(" · Key $it") } ?: append(" · 未配置 Key")
                                        if (model.isVerified) append(" · 已验证")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = when (model.providerId) {
                                "deepseek" -> deepseekKey
                                "agnes" -> agnesKey
                                else -> ""
                            },
                            onValueChange = {
                                when (model.providerId) {
                                    "deepseek" -> deepseekKey = it
                                    "agnes" -> agnesKey = it
                                }
                            },
                            label = { Text("${model.label} Key（sk-…）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val key = when (model.providerId) {
                                        "deepseek" -> deepseekKey
                                        "agnes" -> agnesKey
                                        else -> ""
                                    }.trim()
                                    if (key.isBlank()) {
                                        snackbar.show("请先填写 ${model.label} 的 Key")
                                        return@launch
                                    }
                                    // Key 维度按站点分池（Agnes：国际站 agnes / 中国站 agnes-cn；DeepSeek 原样）
                                    val scopedId = agnesScopedConfigId(model.providerId, graph.providerPrefs.agnesRegion)
                                    graph.keyVault.save(scopedId, model.providerId, key)
                                    // 同步写 provider_configs（Agnes 需同时写 video 与 text 两行；DeepSeek 写 text 行）
                                    val now = System.currentTimeMillis()
                                    graph.db.providerConfigDao().upsert(
                                        ProviderConfigEntity(
                                            configId = "$scopedId-text",
                                            channel = "text",
                                            providerId = model.providerId,
                                            model = model.modelId,
                                            keyMasked = graph.keyVault.masked(scopedId),
                                            isVerified = false,
                                            updatedAt = now,
                                        ),
                                    )
                                    if (model.providerId == AgnesProvider.PROVIDER_ID) {
                                        graph.db.providerConfigDao().upsert(
                                            ProviderConfigEntity(
                                                configId = "$scopedId-video",
                                                channel = "video",
                                                providerId = model.providerId,
                                                model = "video",
                                                keyMasked = graph.keyVault.masked(scopedId),
                                                isVerified = false,
                                                updatedAt = now,
                                            ),
                                        )
                                    }
                                    graph.refreshConfigs()
                                    val r = graph.textRouter.validate(model.modelId)
                                    if (r.isSuccess) {
                                        graph.db.providerConfigDao().get("$scopedId-text")?.let {
                                            graph.db.providerConfigDao().upsert(it.copy(isVerified = true))
                                        }
                                        graph.refreshConfigs()
                                        snackbar.show("${model.label} Key 已保存并连通（${r.getOrThrow().latencyMs ?: "-"}ms）")
                                    } else {
                                        snackbar.show("${model.label} Key 已保存，连通失败：${r.exceptionOrNull()?.message}")
                                    }
                                }
                            }) { Text("保存并验证") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val scopedId = agnesScopedConfigId(model.providerId, graph.providerPrefs.agnesRegion)
                                    graph.keyVault.delete(scopedId)
                                    graph.db.providerConfigDao().delete("$scopedId-text")
                                    if (model.providerId == AgnesProvider.PROVIDER_ID) {
                                        graph.db.providerConfigDao().delete("$scopedId-video")
                                    }
                                    graph.refreshConfigs()
                                    snackbar.show("${model.label} Key 已清除")
                                }
                            }) { Text("清除") }
                        }
                    }
                }
            }

            // ── 视频通道（Agnes） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("视频通道（Agnes 出片）", style = MaterialTheme.typography.titleMedium)
                    Text("视频 Key 与上方「Agnes 文本」共用同一把 Key，保存/验证后在文本模型区块完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("当前视频 Key：${agnesSummary(configs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Agnes Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it },
                        label = { Text("视频提交限速间隔（秒）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = {
                        graph.providerPrefs.videoIntervalMs = interval.toLongOrNull()?.times(1000) ?: 120_000L
                        graph.providerPrefs.agnesBaseUrl = baseUrl.trim()
                        snackbar.show("设置已保存")
                    }) { Text("保存设置") }
                    Text(
                        "国产 ROM 指引：若一键成片在后台被系统杀掉，请在系统设置中为阅读器开启「自启动 / 后台运行 / 电池优化白名单」，并将本页视频通道 Key 配置完毕后再发起流水线。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // ---- 图像通道（对齐源工程 ImageModelBlock：三通道 Key 状态透明） ----
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("图像通道（资产图 / 封面图）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "资产图与封面图由图像通道生成。当前实现复用上方 Agnes Key（同一把 Key 打通文本/视频/图像三通道），无需单独配置；若文本模型选 DeepSeek、视频/图像走 Agnes，请在上方文本模型区块保存 Agnes Key 后此处即生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "当前图像 Key：${imageSummary(configs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
}

private fun agnesSummary(configs: List<ProviderConfigEntity>): String =
    configs.firstOrNull { it.providerId == "agnes" && it.channel == "video" }?.keyMasked?.let { "已配置 $it" } ?: "未配置"

/** 图像通道 Key 状态：与 Agnes 文本/视频共用同一把 Key */
private fun imageSummary(configs: List<ProviderConfigEntity>): String =
    configs.firstOrNull { it.providerId == "agnes" && it.channel == "text" }?.keyMasked?.let { "已配置 $it" } ?: "未配置"