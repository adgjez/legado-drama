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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.legado.drama.AppGraph
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.engine.queue.QueueSnapshot
import com.legado.drama.engine.queue.ShotState
import com.legado.drama.provider.AgnesProvider
import com.legado.drama.service.RenderForegroundService
import kotlinx.coroutines.launch
import java.io.File

/** 渲染队列页：快照 + 单镜状态（F09） */
@Composable
fun RenderQueueScreen(graph: AppGraph) {
    val snapshot by graph.renderQueue.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 快照带 episodeId 时顺带读单镜
    val episodeId = snapshot.episodeId
    if (episodeId != null) {
        val flow = remember(episodeId) { graph.db.renderTaskDao().observeByEpisode(episodeId) }
        val list by flow.collectAsState(initial = emptyList())
        RenderQueueBody(graph, snapshot, list, scope)
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
            RenderQueueBody(graph, snapshot, list, scope)
            return
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("渲染队列", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("暂无剧集，请先在首页生成剧本。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenderQueueBody(
    graph: AppGraph,
    snapshot: QueueSnapshot,
    tasks: List<RenderTaskEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("渲染队列", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (snapshot.total > 0) {
            Text(
                "完成 ${snapshot.completed}/${snapshot.total} · 失败 ${snapshot.failed} · 排队 ${snapshot.pending}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (snapshot.total > 0) snapshot.completed / snapshot.total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            snapshot.pausedReason?.let {
                Spacer(Modifier.height(6.dp))
                Text("暂停原因：$it", color = MaterialTheme.colorScheme.error)
            }
            snapshot.lastMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("队列空闲", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val ep = snapshot.episodeId ?: return@Button
                RenderForegroundService.start(graph.appContext, ep)
            }) { Text("开始/恢复渲染") }
            Button(onClick = {
                graph.scope.launch { graph.renderQueue.pause("user") }
            }) { Text("暂停") }
        }
        Spacer(Modifier.height(12.dp))
        if (tasks.isEmpty()) {
            Text("暂无渲染任务（episode=${snapshot.episodeId ?: "-"}）", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.shotId }) { t ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("镜 ${t.shotId.takeLast(4)} · 状态 ${t.state}", style = MaterialTheme.typography.bodyMedium)
                            t.failReason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 评审画廊页：资产 keep/regen（F04 硬门槛） */
@Composable
fun GalleryScreen(
    graph: AppGraph,
    snackbar: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull()
    if (project == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("评审画廊", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("暂无项目。请先在首页用 AI 一键成片生成项目。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // 进入画廊时同步六铁律白名单上下文（不触发重组，仅赋值）
    androidx.compose.runtime.LaunchedEffect(project.projectId) {
        graph.setActiveProject(project.projectId)
    }
    val assets by graph.db.assetDao().observeByProject(project.projectId).collectAsState(initial = emptyList())
    val eps by graph.db.episodeDao().observeByProject(project.projectId).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("评审画廊 · ${project.name}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("流程：保留全部资产 → 全部置 keep → 评审通过后可渲染", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    graph.db.assetDao().updateReviewState(assets.map { it.assetId }, "keep")
                    snackbar.showSnackbar("已全部标记保留")
                }
            }) { Text("全部保留") }
            Button(onClick = {
                scope.launch {
                    val ep = eps.firstOrNull() ?: return@launch
                    graph.db.episodeDao().setReviewPassed(ep.episodeId, true)
                    snackbar.showSnackbar("评审已通过，可进入渲染")
                }
            }) { Text("标记评审通过") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(assets, key = { it.assetId }) { a ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${a.kind} ${a.poseRole ?: ""} · G1=${a.g1State} · 评审=${a.reviewState}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(a.prompt.take(60) + if (a.prompt.length > 60) "…" else "", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    graph.db.assetDao().updateReviewState(listOf(a.assetId), "keep")
                                }
                            }) { Text("保留") }
                            Button(onClick = {
                                scope.launch {
                                    graph.db.assetDao().updateReviewState(listOf(a.assetId), "regen")
                                }
                            }) { Text("重生成") }
                        }
                    }
                }
            }
        }
    }
}

/** 成片库页：finished_films 列表 + 播放/分享/删除 + 仅当全部单镜 COMPLETED 才可合成 */
@Composable
fun FilmsScreen(
    graph: AppGraph,
    snackbar: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val films by graph.db.finishedFilmDao().observeAll().collectAsState(initial = emptyList())
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull()
    var deleteTarget by remember { mutableStateOf<FinishedFilmEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("成片库", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (project != null) {
            val eps by graph.db.episodeDao().observeByProject(project.projectId).collectAsState(initial = emptyList())
            val ep = eps.firstOrNull()
            if (ep != null) {
                val renderFlow = remember(ep.episodeId) { graph.db.renderTaskDao().observeByEpisode(ep.episodeId) }
                val renderTasks by renderFlow.collectAsState(initial = emptyList())
                val allCompleted = renderTasks.isNotEmpty() &&
                    renderTasks.all { it.state == ShotState.COMPLETED.name && !it.localFileUri.isNullOrBlank() }
                Button(
                    enabled = allCompleted,
                    onClick = {
                        scope.launch {
                            val clips = renderTasks
                                .filter { it.state == ShotState.COMPLETED.name && !it.localFileUri.isNullOrBlank() }
                                .mapNotNull { File(it.localFileUri!!).takeIf { f -> f.exists() && f.length() > 0 } }
                            if (clips.isEmpty()) {
                                snackbar.showSnackbar("没有已落盘的单镜片段，请先完成渲染")
                                return@launch
                            }
                            val output = File(graph.appContext.filesDir, "movies/${ep.episodeId}.mp4").apply { parentFile?.mkdirs() }
                            when (val r = graph.movieAssembler.assemble(clips, output)) {
                                is com.legado.drama.engine.assemble.MovieAssembler.AssembleResult.Success -> {
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
                                    snackbar.showSnackbar("成片完成：${r.strategy.label}")
                                }
                                is com.legado.drama.engine.assemble.MovieAssembler.AssembleResult.Segmented -> {
                                    snackbar.showSnackbar("分段导出 ${r.parts.size} 段（尚未合并）")
                                }
                                is com.legado.drama.engine.assemble.MovieAssembler.AssembleResult.Failure -> {
                                    snackbar.showSnackbar("合成失败：${r.message}")
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        if (allCompleted) "合成整集成片" else "合成（需全部单镜渲染完成）",
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("当前剧集：${ep.episodeId.take(8)}…（${eps.size} 集 · 已渲染 ${renderTasks.count { it.state == ShotState.COMPLETED.name }}/${renderTasks.size}）", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (films.isEmpty()) {
            Text("还没有成片。", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(films, key = { it.filmId }) { f ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${f.filmId.take(8)}… · ${f.strategy}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "时长 ${"%.1f".format(f.durationSeconds)}s · ${f.fileSize / 1024}KB · ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.assembledAt))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { playFilm(graph, f) }) { Text("播放") }
                                Button(onClick = { shareFilm(graph, f) }) { Text("分享") }
                                Button(onClick = { deleteTarget = f }) { Text("删除") }
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
                        snackbar.showSnackbar("成片已删除")
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

/** 设置页：Key 录入（Keystore 加密）+ 文本模型区块 + 限速 + base url */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    snackbar: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var agnesKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(graph.providerPrefs.videoIntervalMs.toString()) }
    var baseUrl by remember { mutableStateOf(graph.providerPrefs.agnesBaseUrl) }
    val configs by graph.db.providerConfigDao().observeAll().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // ── 文本模型区块（T014 §2.3 Q4：DeepSeek/Agnes 双模型、随时互切） ──
        Text("文本模型（剧本/分镜大脑）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("当前生效：${graph.textRouter.activeTextModelId()}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val registered = remember { graph.textRouter.registeredTextModels() }
        var activeModelId by remember { mutableStateOf(graph.textRouter.activeTextModelId()) }
        registered.forEach { model ->
            val selected = model.modelId == activeModelId
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, onClick = {
                        activeModelId = model.modelId
                        scope.launch {
                            graph.textRouter.setActiveTextModel(model.modelId)
                            snackbar.showSnackbar("文本模型已切换：${model.label}")
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
                            snackbar.showSnackbar("请先填写 ${model.label} 的 Key")
                            return@launch
                        }
                        graph.keyVault.save(model.providerId, model.providerId, key)
                        // 同步写 provider_configs（Agnes 需同时写 video 与 text 两行；DeepSeek 写 text 行）
                        val now = System.currentTimeMillis()
                        graph.db.providerConfigDao().upsert(
                            ProviderConfigEntity(
                                configId = "${model.providerId}-text",
                                channel = "text",
                                providerId = model.providerId,
                                model = model.modelId,
                                keyMasked = graph.keyVault.masked(model.providerId),
                                isVerified = false,
                                updatedAt = now,
                            ),
                        )
                        if (model.providerId == AgnesProvider.PROVIDER_ID) {
                            graph.db.providerConfigDao().upsert(
                                ProviderConfigEntity(
                                    configId = "${model.providerId}-video",
                                    channel = "video",
                                    providerId = model.providerId,
                                    model = "video",
                                    keyMasked = graph.keyVault.masked(model.providerId),
                                    isVerified = false,
                                    updatedAt = now,
                                ),
                            )
                        }
                        graph.refreshConfigs()
                        val r = graph.textRouter.validate(model.modelId)
                        if (r.isSuccess) {
                            graph.db.providerConfigDao().upsert(
                                graph.db.providerConfigDao().get("${model.providerId}-text")?.copy(isVerified = true) ?: return@launch,
                            )
                            graph.refreshConfigs()
                            snackbar.showSnackbar("${model.label} Key 已保存并连通（${r.getOrThrow().latencyMs ?: "-"}ms）")
                        } else {
                            snackbar.showSnackbar("${model.label} Key 已保存，连通失败：${r.exceptionOrNull()?.message}")
                        }
                    }
                }) { Text("保存并验证") }
                Button(onClick = {
                    scope.launch {
                        graph.keyVault.delete(model.providerId)
                        graph.db.providerConfigDao().delete("${model.providerId}-text")
                        if (model.providerId == AgnesProvider.PROVIDER_ID) {
                            graph.db.providerConfigDao().delete("${model.providerId}-video")
                        }
                        graph.refreshConfigs()
                        snackbar.showSnackbar("${model.label} Key 已清除")
                    }
                }) { Text("清除") }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 视频通道（Agnes） ──
        Text("视频通道（Agnes 出片）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("视频 Key 与上方「Agnes 文本」共用同一把 Key，保存/验证后在文本模型区块完成。", style = MaterialTheme.typography.bodySmall)
        Text("当前视频 Key：${agnesSummary(configs)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Agnes Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it },
            label = { Text("视频提交限速间隔（秒）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            graph.providerPrefs.videoIntervalMs = interval.toLongOrNull()?.times(1000) ?: 120_000L
            graph.providerPrefs.agnesBaseUrl = baseUrl.trim()
            scope.launch { snackbar.showSnackbar("设置已保存") }
        }) { Text("保存设置") }

        Spacer(Modifier.height(16.dp))
        Text("国产 ROM 指引：若一键成片在后台被系统杀掉，请在系统设置中为阅读器开启「自启动 / 后台运行 / 电池优化白名单」，并将本页视频通道 Key 配置完毕后再发起流水线。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun agnesSummary(configs: List<ProviderConfigEntity>): String =
    configs.firstOrNull { it.providerId == "agnes" && it.channel == "video" }?.keyMasked?.let { "已配置 $it" } ?: "未配置"