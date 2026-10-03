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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.legado.drama.AppGraph
import com.legado.drama.ProviderPrefs
import com.legado.drama.R
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.engine.assemble.MovieAssembler
import com.legado.drama.engine.queue.QueueSnapshot
import com.legado.drama.engine.queue.ShotState
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.provider.AgnesRegion
import com.legado.drama.provider.VideoProviderRouter
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
        PageHeader(title = stringResource(R.string.queue_title), subtitle = stringResource(R.string.queue_subtitle))
        EmptyState(
            icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
            title = stringResource(R.string.queue_no_episode_title),
            subtitle = stringResource(R.string.queue_no_episode_subtitle),
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var budgetConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = stringResource(R.string.queue_title), subtitle = stringResource(R.string.queue_subtitle))

        // ---- 总进度卡（对齐源工程 QueuePage 总进度 DramaCard） ----
        DramaCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val epDisplay = snapshot.episodeId?.substringAfterLast("_")?.takeIf { it.startsWith("ep", ignoreCase = true) }
                    ?: snapshot.episodeId
                if (snapshot.total > 0) {
                    Text(
                        stringResource(R.string.queue_episode_progress, epDisplay ?: "-", snapshot.completed, snapshot.total),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (snapshot.total > 0) snapshot.completed / snapshot.total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.queue_progress_summary,
                            snapshot.completed, snapshot.failed, snapshot.pending, snapshot.submittedInFlight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    snapshot.pausedReason?.let {
                        Text(stringResource(R.string.queue_paused_reason, stringResource(pauseLabelRes(it))), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    snapshot.lastMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(stringResource(R.string.queue_idle), style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val resumeText = if (snapshot.pausedReason != null) {
                        stringResource(R.string.queue_resume_btn)
                    } else stringResource(R.string.queue_start_btn)
                    PrimaryButton(
                        text = resumeText,
                        onClick = {
                            val reason = snapshot.pausedReason
                            if (reason == null) {
                                val ep = snapshot.episodeId
                                if (ep != null) {
                                    RenderForegroundService.start(graph.appContext, ep)
                                    snackbar.show(context.getString(R.string.queue_service_started))
                                }
                            } else if (reason == "budget") {
                                // 预算达上限：必须显式确认放行（引擎 resume(confirmedByUser=true)）
                                budgetConfirm = true
                            } else {
                                graph.scope.launch {
                                    graph.renderQueue.resume(confirmedByUser = false)
                                    snackbar.show(context.getString(R.string.queue_resumed))
                                }
                            }
                        },
                        enabled = snapshot.episodeId != null,
                    )
                    OutlinedButton(onClick = {
                        graph.scope.launch { graph.renderQueue.pause("user") }
                        snackbar.show(context.getString(R.string.queue_paused))
                    }, enabled = snapshot.total > 0) { Text(stringResource(R.string.queue_pause_btn)) }
                }
            }
        }

        // ---- 预算达标上限确认弹窗（对齐源工程预算确认放行位语义） ----
        if (budgetConfirm) {
            AlertDialog(
                onDismissRequest = { budgetConfirm = false },
                title = { Text(stringResource(R.string.queue_budget_title)) },
                text = { Text(stringResource(R.string.queue_budget_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        graph.scope.launch {
                            graph.renderQueue.resume(confirmedByUser = true)
                            snackbar.show(context.getString(R.string.queue_budget_confirm_snack))
                        }
                        budgetConfirm = false
                    }) { Text(stringResource(R.string.queue_budget_confirm_btn)) }
                },
                dismissButton = {
                    TextButton(onClick = { budgetConfirm = false }) { Text(stringResource(R.string.queue_budget_dismiss_btn)) }
                },
            )
        }

        if (tasks.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = stringResource(R.string.queue_empty_title),
                subtitle = stringResource(R.string.queue_empty_subtitle, snapshot.episodeId ?: "-"),
            )
        } else {
            // ---- 单镜任务列表（对齐源工程 QueuePage 镜状态卡） ----
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.shotId }) { t ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.queue_shot_title, t.shotId.takeLast(4)), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.queue_shot_state, stringResource(shotStateLabelRes(t.state))), color = shotStateColor(t.state), style = MaterialTheme.typography.bodySmall)
                            }
                            t.failReason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            t.blockedReason?.let {
                                Text(stringResource(R.string.queue_shot_blocked, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            // 单镜取消（对齐源工程 QueuePage 取消按钮，cancelShot 终止该镜）
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    graph.renderQueue.cancelShot(t.shotId)
                                    snackbar.show(context.getString(R.string.queue_shot_cancelled, t.shotId.takeLast(4)))
                                }) { Text(stringResource(R.string.queue_cancel_btn)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun pauseLabelRes(reason: String): Int = when (reason) {
    "budget" -> R.string.queue_pause_budget
    "network" -> R.string.queue_pause_network
    "auth" -> R.string.queue_pause_auth
    "review" -> R.string.queue_pause_review
    "noshots" -> R.string.queue_pause_noshots
    "user" -> R.string.queue_pause_user
    else -> R.string.queue_pause_budget
}

@androidx.annotation.StringRes
private fun shotStateLabelRes(state: String): Int = when (state) {
    ShotState.PENDING.name -> R.string.queue_state_pending
    ShotState.SUBMITTED.name -> R.string.queue_state_submitted
    ShotState.COMPLETED.name -> R.string.queue_state_completed
    ShotState.FAILED.name -> R.string.queue_state_failed
    ShotState.BLOCKED.name -> R.string.queue_state_blocked
    else -> R.string.queue_state_pending
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val films by graph.db.finishedFilmDao().observeAll().collectAsState(initial = emptyList())
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull()
    var deleteTarget by remember { mutableStateOf<FinishedFilmEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = stringResource(R.string.library_title), subtitle = stringResource(R.string.library_subtitle))

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
                        Text(stringResource(R.string.library_current_assemble), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.library_episode_summary,
                                ep.epNo, eps.size,
                                renderTasks.count { it.state == ShotState.COMPLETED.name }, renderTasks.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        PrimaryButton(
                            text = if (allCompleted) stringResource(R.string.library_assemble_ready) else stringResource(R.string.library_assemble_waiting),
                            onClick = {
                                scope.launch {
                                    // ★ 顺序契约：组装要求按 shot_no 升序。render_tasks.shot_id 是
                                    // UUID 主键，直接按该列排序与分镜顺序无关；以 shots 表（ORDER BY
                                    // shot_no）为基准按 shotId 映射提取已完成片段。
                                    val shots = graph.db.shotDao().listByEpisode(ep.episodeId)
                                    val tasksByShot = graph.db.renderTaskDao().listByEpisode(ep.episodeId)
                                        .associateBy { it.shotId }
                                    val clips = shots
                                        .mapNotNull { tasksByShot[it.shotId] }
                                        .filter { it.state == ShotState.COMPLETED.name && !it.localFileUri.isNullOrBlank() }
                                        .mapNotNull { File(it.localFileUri!!).takeIf { f -> f.exists() && f.length() > 0 } }
                                    if (clips.isEmpty()) {
                                        snackbar.show(context.getString(R.string.library_no_clips))
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
                                            snackbar.show(context.getString(R.string.library_assemble_done, r.strategy.label))
                                        }
                                        is MovieAssembler.AssembleResult.Segmented -> {
                                            snackbar.show(context.getString(R.string.library_assemble_segmented, r.parts.size))
                                        }
                                        is MovieAssembler.AssembleResult.Failure -> {
                                            snackbar.show(context.getString(R.string.library_assemble_failed, r.message))
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
                title = stringResource(R.string.library_empty_title),
                subtitle = stringResource(R.string.library_empty_subtitle),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(films, key = { it.filmId }) { f ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.library_film_title, f.filmId.substringAfterLast("_", f.filmId), f.strategy), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.library_film_meta, f.durationSeconds, f.fileSize / 1024),
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
                                Button(onClick = { playFilm(graph, f) }) { Text(stringResource(R.string.library_play_btn)) }
                                OutlinedButton(onClick = { shareFilm(graph, f) }) { Text(stringResource(R.string.library_share_btn)) }
                                OutlinedButton(onClick = { deleteTarget = f }) { Text(stringResource(R.string.library_delete_btn)) }
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
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_text, target.filmId.take(8))) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.db.finishedFilmDao().delete(target.filmId)
                        File(target.fileUri).takeIf { it.exists() }?.delete()
                        snackbar.show(context.getString(R.string.library_deleted))
                    }
                    deleteTarget = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
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
    context.startActivity(Intent.createChooser(intent, graph.appContext.getString(R.string.library_share_chooser)))
}

/**
 * 设置页（子页，对齐源工程 SettingsPage 结构）：
 * PageHeader + 分区 DramaCard（文本模型区块 / 视频通道区块）。
 * Key 录入通过 Keystore 加密（AndroidKeyVault），不落明文。
 */
@Composable
fun SettingsPage(graph: AppGraph) {
    val snackbar = LocalDramaSnackbar.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var agnesKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var mimoKey by remember { mutableStateOf("") }
    var agnesImageKey by remember { mutableStateOf("") }
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
        snackbar.show(
            context.getString(
                R.string.settings_agnes_switched,
                if (target == AgnesRegion.CHINA) context.getString(R.string.settings_agnes_cn) else context.getString(R.string.settings_agnes_intl),
            ),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            PageHeader(title = stringResource(R.string.settings_title), subtitle = stringResource(R.string.settings_subtitle))

            // ── Agnes 站点分池（对齐源工程 AgnesRegion 卡片：两站 Key 独立） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_agnes_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.settings_agnes_current,
                                stringResource(if (agnesRegion == AgnesRegion.CHINA) R.string.settings_agnes_cn else R.string.settings_agnes_intl),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_agnes_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DramaFilterChip(
                            selected = agnesRegion == AgnesRegion.INTERNATIONAL,
                            onClick = { switchAgnesRegion(AgnesRegion.INTERNATIONAL) },
                            label = { Text(stringResource(R.string.settings_agnes_intl)) },
                        )
                        DramaFilterChip(
                            selected = agnesRegion == AgnesRegion.CHINA,
                            onClick = { switchAgnesRegion(AgnesRegion.CHINA) },
                            label = { Text(stringResource(R.string.settings_agnes_cn)) },
                        )
                    }
                }
            }

            // ── 文本模型区块（T014 §2.3 Q4：DeepSeek/Agnes 双模型、随时互切） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_text_model_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_text_model_active, graph.textRouter.activeTextModelId()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
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
                                        snackbar.show(context.getString(R.string.settings_text_model_switched, model.label))
                                    }
                                }),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(model.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        append(context.getString(R.string.settings_model_id, model.modelId))
                                        model.keyMasked?.let { append(context.getString(R.string.settings_model_key, it)) } ?: append(context.getString(R.string.settings_model_no_key))
                                        if (model.isVerified) append(context.getString(R.string.settings_model_verified))
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
                                "mimo" -> mimoKey
                                else -> ""
                            },
                            onValueChange = {
                                when (model.providerId) {
                                    "deepseek" -> deepseekKey = it
                                    "agnes" -> agnesKey = it
                                    "mimo" -> mimoKey = it
                                }
                            },
                            label = { Text(context.getString(R.string.settings_model_key_label, model.label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val key = when (model.providerId) {
                                        "deepseek" -> deepseekKey
                                        "agnes" -> agnesKey
                                        "mimo" -> mimoKey
                                        else -> ""
                                    }.trim()
                                    if (key.isBlank()) {
                                        snackbar.show(context.getString(R.string.settings_key_missing, model.label))
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
                                        snackbar.show(
                                            context.getString(
                                                R.string.settings_key_saved_ok,
                                                model.label, r.getOrThrow().latencyMs ?: "-",
                                            ),
                                        )
                                    } else {
                                        snackbar.show(
                                            context.getString(
                                                R.string.settings_key_saved_fail,
                                                model.label, r.exceptionOrNull()?.message ?: "?",
                                            ),
                                        )
                                    }
                                }
                            }) { Text(stringResource(R.string.settings_key_save_btn)) }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val scopedId = agnesScopedConfigId(model.providerId, graph.providerPrefs.agnesRegion)
                                    graph.keyVault.delete(scopedId)
                                    graph.db.providerConfigDao().delete("$scopedId-text")
                                    if (model.providerId == AgnesProvider.PROVIDER_ID) {
                                        graph.db.providerConfigDao().delete("$scopedId-video")
                                    }
                                    graph.refreshConfigs()
                                    snackbar.show(context.getString(R.string.settings_key_cleared, model.label))
                                }
                            }) { Text(stringResource(R.string.settings_key_clear_btn)) }
                        }
                    }
                }
            }

            // ── 视频通道（P0-③ 供应商路由：Agnes 默认 / Kling，Key 独立分池） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var videoProviderId by remember {
                        mutableStateOf(graph.activeVideoProviderId())
                    }
                    var klingKey by remember { mutableStateOf("") }
                    Text(stringResource(R.string.settings_video_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_video_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        stringResource(
                            R.string.settings_video_active_hint,
                            stringResource(
                                if (videoProviderId == "kling") R.string.settings_video_provider_kling
                                else R.string.settings_video_provider_agnes,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // ── 供应商选择（RadioButton 列表，对齐源工程 SettingsPage 供应商区块） ──
                    listOf(
                        "agnes" to stringResource(R.string.settings_video_provider_agnes),
                        "kling" to stringResource(R.string.settings_video_provider_kling),
                    ).forEach { (providerId, label) ->
                        val selected = providerId == videoProviderId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, onClick = {
                                    videoProviderId = providerId
                                    graph.setActiveVideoProvider(providerId)
                                    snackbar.show(context.getString(R.string.settings_video_switched, label))
                                }),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // ── Kling 独立 Key 输入（configId = "kling-video" 分池） ──
                    if (videoProviderId == "kling") {
                        OutlinedTextField(
                            value = klingKey,
                            onValueChange = { klingKey = it },
                            label = { Text(stringResource(R.string.settings_video_kling_key_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val key = klingKey.trim()
                                    if (key.isBlank()) {
                                        snackbar.show(context.getString(R.string.settings_video_kling_key_missing))
                                        return@launch
                                    }
                                    val configId = VideoProviderRouter.configIdFor("kling", graph.providerPrefs.agnesRegion)
                                    graph.keyVault.save(configId, "kling", key)
                                    klingKey = ""
                                    graph.db.providerConfigDao().upsert(
                                        ProviderConfigEntity(
                                            configId = "$configId-video",
                                            channel = "video",
                                            providerId = "kling",
                                            model = "video",
                                            keyMasked = graph.keyVault.masked(configId),
                                            isVerified = false,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    graph.refreshConfigs()
                                    snackbar.show(context.getString(R.string.settings_video_kling_key_saved))
                                }
                            }) { Text(stringResource(R.string.settings_key_save_btn)) }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val configId = VideoProviderRouter.configIdFor("kling", graph.providerPrefs.agnesRegion)
                                    graph.keyVault.delete(configId)
                                    graph.db.providerConfigDao().delete("$configId-video")
                                    graph.refreshConfigs()
                                    snackbar.show(context.getString(R.string.settings_video_kling_key_cleared))
                                }
                            }) { Text(stringResource(R.string.settings_key_clear_btn)) }
                        }
                    } else {
                        // ── Agnes 通道：Base URL + 限速间隔（Key 与文本模型区块共用 agnes 池） ──
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(stringResource(R.string.settings_base_url_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = interval,
                            onValueChange = { interval = it },
                            label = { Text(stringResource(R.string.settings_interval_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(onClick = {
                            graph.providerPrefs.videoIntervalMs = interval.toLongOrNull()?.times(1000) ?: 120_000L
                            graph.providerPrefs.agnesBaseUrl = baseUrl.trim()
                            snackbar.show(context.getString(R.string.settings_saved))
                        }) { Text(stringResource(R.string.settings_save_btn)) }
                    }
                    Text(
                        stringResource(R.string.settings_rom_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // ── 图像通道（对齐源工程 ImageModelBlock：独立 Agnes 图像 Key） ──
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_image_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_image_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        stringResource(R.string.settings_image_current, imageSummary(context, graph, agnesRegion)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    OutlinedTextField(
                        value = agnesImageKey,
                        onValueChange = { agnesImageKey = it },
                        label = { Text(stringResource(R.string.settings_image_key_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val key = agnesImageKey.trim()
                                if (key.isBlank()) {
                                    snackbar.show(context.getString(R.string.settings_image_key_missing))
                                    return@launch
                                }
                                val scopedImageId = graph.agnesImageKeyId()
                                graph.keyVault.save(scopedImageId, AgnesProvider.PROVIDER_ID, key)
                                agnesImageKey = ""
                                // 同步 provider_configs image 行（对齐文本/视频区块的持久化模式）
                                graph.db.providerConfigDao().upsert(
                                    ProviderConfigEntity(
                                        configId = "$scopedImageId-image",
                                        channel = "image",
                                        providerId = AgnesProvider.PROVIDER_ID,
                                        model = "image",
                                        keyMasked = graph.keyVault.masked(scopedImageId),
                                        isVerified = false,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                graph.refreshConfigs()
                                snackbar.show(context.getString(R.string.settings_image_key_saved))
                            }
                        }) { Text(stringResource(R.string.settings_image_save_btn)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val scopedImageId = graph.agnesImageKeyId()
                                graph.keyVault.delete(scopedImageId)
                                graph.db.providerConfigDao().delete("$scopedImageId-image")
                                graph.refreshConfigs()
                                snackbar.show(context.getString(R.string.settings_image_key_cleared))
                            }
                        }) { Text(stringResource(R.string.settings_key_clear_btn)) }
                    }
                }
            }
        }
}

/**
 * 图像通道 Key 状态：优先展示独立图像 Key（agnes-image 分池掩码），
 * 未单独配置时提示回退复用共享 Agnes Key（与 generateImage 运行时取 Key 链一致）。
 */
private fun imageSummary(context: android.content.Context, graph: AppGraph, region: AgnesRegion): String {
    val imageMasked = graph.keyVault.masked(graph.agnesImageKeyId())
    if (imageMasked.isNotEmpty()) return context.getString(R.string.settings_image_key_standalone, imageMasked)
    val sharedMasked = graph.keyVault.masked(
        agnesScopedConfigId(AgnesProvider.PROVIDER_ID, region),
    )
    return if (sharedMasked.isNotEmpty()) {
        context.getString(R.string.settings_image_key_fallback, sharedMasked)
    } else context.getString(R.string.settings_key_not_configured)
}