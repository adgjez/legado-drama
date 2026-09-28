package com.legado.drama.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.legado.drama.AppGraph
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.DramaFilterChip
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.HeroButton
import com.legado.drama.ui.components.IconActionButton
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import com.legado.drama.ui.components.PrimaryButton
import com.legado.drama.ui.components.statusErr
import com.legado.drama.ui.components.statusInfo
import com.legado.drama.ui.components.statusOk
import kotlinx.coroutines.launch
import java.io.File

/** 拍摄类型（决定权限请求后启动哪个相机 Launcher） */
private enum class CaptureKind { IMAGE }

/** 资产 kind → 中文标签（对齐源工程资产类型筛选） */
private fun kindLabel(kind: String): String = when (kind) {
    "character" -> "角色"
    "scene" -> "场景"
    "prop" -> "道具"
    "local" -> "本地"
    else -> kind
}

/**
 * 资产库页（S3/S4，对齐源工程 AssetsPage 网格结构）：
 * kind 筛选 chips + 双列资产网格 + 评审操作（F04 硬门槛：keep/regen、
 * 全部保留、标记评审通过后可渲染）。legado 资产由 AI 流水线自动生成，
 * 本页只负责审核与放行。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AssetsPage(
    graph: AppGraph,
    projectId: String,
    onContinue: () -> Unit,
) {
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()
    val projects by graph.db.projectDao().observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull { it.projectId == projectId } ?: projects.firstOrNull()
    // 无项目兜底
    if (project == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyState(
                icon = { Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "暂无项目",
                subtitle = "请先在「项目」页用 AI 一键成片生成项目。",
            )
        }
        return
    }
    // 进入资产页时同步六铁律白名单上下文
    LaunchedEffect(project.projectId) {
        graph.setActiveProject(project.projectId)
    }
    val assets by graph.db.assetDao().observeByProject(project.projectId).collectAsState(initial = emptyList())
    val eps by graph.db.episodeDao().observeByProject(project.projectId).collectAsState(initial = emptyList())
    var kindFilter by remember { mutableStateOf("全部") }
    val kinds = listOf("全部") + assets.map { it.kind }.distinct().sorted()

    val filtered = if (kindFilter == "全部") assets else assets.filter { it.kind == kindFilter }

    val keptCount = assets.count { it.reviewState == "keep" }
    val allKept = assets.isNotEmpty() && assets.all { it.reviewState == "keep" }

    // ---- 本地上传（对齐源工程第六轮：拍摄/相册图/相册视频 + copyToInternal 稳定落盘） ----
    val ctx = LocalContext.current
    val uploadScope = rememberCoroutineScope()
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var previewAsset by remember { mutableStateOf<AssetEntity?>(null) }   // 资产卡点击预览（本地资产）

    /** 拍摄输出 URI：cacheDir/capture/（宿主 file_paths 已暴露 cache 根路径） */
    fun captureUri(): Uri {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileProvider", f)
    }

    /** 拍摄成功/相册选择后：拷贝到 filesDir/uploads/ 再落库（content:// 权限仅在回调内有效） */
    fun uploadVia(uri: Uri, kind: String, prompt: String, isVideo: Boolean) {
        uploadScope.launch {
            val internal = AssetFiles.copyToInternal(ctx, uri, isVideo = isVideo)
            if (internal == null) {
                captureError = if (isVideo) "视频读取失败（可能已无读权限）" else "图片读取失败（可能已无读权限）"
                return@launch
            }
            graph.db.assetDao().upsert(
                AssetEntity(
                    assetId = "local_${System.currentTimeMillis()}_${uri.hashCode()}",
                    projectId = project.projectId,
                    kind = "local",
                    prompt = prompt,
                    fileUri = internal,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            snackbar.show("已上传本地$kind")
        }
    }

    // 拍摄图片：TakePicture 回调 Boolean，仅 success=true 才落库（空文件/取消不入库）
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val u = pendingCaptureUri
        pendingCaptureUri = null
        if (success && u != null) {
            uploadVia(u, "图片", "拍摄图片", isVideo = false)
        } else {
            captureError = "已取消拍摄或拍摄失败"
        }
    }
    // 拍摄权限请求：target 34+ 未授权直接启动相机会抛 SecurityException
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val u = captureUri()
            pendingCaptureUri = u
            cameraLauncher.launch(u)
        } else {
            captureError = "拍摄需要相机权限，请在系统设置中授予「相机」权限后重试"
        }
    }
    // 相册图片 / 相册视频（GetContent 免存储权限）
    val albumImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadVia(it, "图片", "相册图片", isVideo = false) }
    }
    val albumVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadVia(it, "视频", "相册视频", isVideo = true) }
    }

    fun startCapture() {
        captureError = null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            val u = captureUri()
            pendingCaptureUri = u
            runCatching { cameraLauncher.launch(u) }
                .onFailure { captureError = "无法启动相机：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    // 资产点击预览：有 fileUri/remoteUrl（本地或已生成）时弹大图
    fun previewTarget(a: AssetEntity): String? = a.fileUri ?: a.remoteUrl

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            PageHeader(title = "资产库", subtitle = "${project.name} · 评审通过后可渲染（F04 硬门槛）")
        }
        item(span = { GridItemSpan(2) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in kinds) {
                    DramaFilterChip(
                        selected = kindFilter == k,
                        onClick = { kindFilter = k },
                        label = { Text(if (k == "全部") "全部（${assets.size}）" else "${kindLabel(k)}（${assets.count { it.kind == k }}）") },
                    )
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("评审进度：保留 $keptCount/${assets.size}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allKept) "全部资产已保留，可标记评审通过进入渲染。"
                        else "流程：保留全部资产 → 全部置 keep → 评审通过后可渲染。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.db.assetDao().updateReviewState(assets.map { it.assetId }, "keep")
                                snackbar.show("已全部标记保留")
                            }
                        }, enabled = assets.isNotEmpty()) { Text("全部保留") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val ep = eps.firstOrNull()
                                if (ep == null) {
                                    snackbar.show("暂无剧集，无法标记评审通过")
                                } else {
                                    graph.db.episodeDao().setReviewPassed(ep.episodeId, true)
                                    snackbar.show("评审已通过，可进入渲染")
                                }
                            }
                        }, enabled = assets.isNotEmpty()) { Text("标记评审通过") }
                    }
                }
            }
        }

        // ---- 本地上传入口（对齐源工程第六轮：拍摄/相册图/相册视频） ----
        item(span = { GridItemSpan(2) }) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地上传", style = MaterialTheme.typography.titleMedium)
                    captureError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconActionButton(
                            label = "拍摄图片",
                            icon = Icons.Filled.PhotoCamera,
                            onClick = { startCapture() },
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(
                            label = "相册图片",
                            icon = Icons.Filled.PhotoLibrary,
                            onClick = { albumImageLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(
                            label = "相册视频",
                            icon = Icons.Filled.VideoLibrary,
                            onClick = { albumVideoLauncher.launch("video/*") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (assets.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                EmptyState(
                    icon = { Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有资产",
                    subtitle = "AI 流水线生成后资产会出现在这里。尚未生成时请回「项目」页用 AI 一键成片跑完整流程。",
                )
            }
        }
        items(filtered, key = { it.assetId }) { a: AssetEntity ->
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 缩略图区（对齐源工程 GridAssetCard：92dp + imageUri/remoteUrl + 失败占位）
                    val thumbModel = a.fileUri ?: a.remoteUrl
                    if (thumbModel != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(92.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { previewAsset = a },
                            contentAlignment = Alignment.Center,
                        ) {
                            GlideImage(
                                model = thumbModel,
                                contentDescription = a.prompt,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                loading = placeholder {},
                                failure = placeholder {
                                    Text(
                                        "图片加载失败",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${kindLabel(a.kind)}${a.poseRole?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "G1=${a.g1State} · 评审=${a.reviewState}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (a.reviewState) {
                            "keep" -> MaterialTheme.colorScheme.primary
                            "regen" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        a.prompt.take(48) + if (a.prompt.length > 48) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.db.assetDao().updateReviewState(listOf(a.assetId), "keep")
                                snackbar.show("${a.assetId.takeLast(6)} 已保留")
                            }
                        }, enabled = a.reviewState != "keep") { Text("保留") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.db.assetDao().updateReviewState(listOf(a.assetId), "regen")
                                snackbar.show("${a.assetId.takeLast(6)} 标记重生成")
                            }
                        }, enabled = a.reviewState != "regen") { Text("重生成") }
                    }
                }
            }
        }
    }

    // ---- 资产大图预览：点击缩略图弹出（本地 fileUri / 已生成 remoteUrl），视频资产仅展示信息 ----
    previewAsset?.let { pa ->
        val model = pa.fileUri ?: pa.remoteUrl
        val isVideo = model?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "webm", "mkv", "mov", "3gp", "avi")
        AlertDialog(
            onDismissRequest = { previewAsset = null },
            confirmButton = {
                OutlinedButton(onClick = { previewAsset = null }) { Text("关闭") }
            },
            title = { Text(kindLabel(pa.kind)) },
            text = {
                when {
                    model == null -> Text("该资产暂无预览图", color = MaterialTheme.colorScheme.outline)
                    isVideo -> Column {
                        Text(pa.prompt.take(48), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "视频资产：${model.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    else -> GlideImage(
                        model = model,
                        contentDescription = pa.prompt,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        contentScale = ContentScale.Fit,
                        loading = placeholder {},
                        failure = placeholder {
                            Text("图片加载失败", color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            },
        )
    }
}