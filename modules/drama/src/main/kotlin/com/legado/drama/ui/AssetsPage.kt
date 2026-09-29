package com.legado.drama.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.legado.drama.AppGraph
import com.legado.drama.R
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

/** 资产 kind → 标签资源 id（对齐源工程资产类型筛选） */
@StringRes
private fun kindLabelRes(kind: String): Int = when (kind) {
    "character" -> R.string.assets_kind_character
    "scene" -> R.string.assets_kind_scene
    "prop" -> R.string.assets_kind_prop
    "local" -> R.string.assets_kind_local
    else -> R.string.assets_kind_local
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
                title = stringResource(R.string.assets_no_project_title),
                subtitle = stringResource(R.string.assets_no_project_subtitle),
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
                captureError = ctx.getString(
                    if (isVideo) R.string.assets_read_fail_video else R.string.assets_read_fail_image,
                )
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
            snackbar.show(ctx.getString(R.string.assets_uploaded, kind))
        }
    }

    // 拍摄图片：TakePicture 回调 Boolean，仅 success=true 才落库（空文件/取消不入库）
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val u = pendingCaptureUri
        pendingCaptureUri = null
        if (success && u != null) {
            uploadVia(u, ctx.getString(R.string.assets_kind_image), ctx.getString(R.string.assets_capture_btn), isVideo = false)
        } else {
            captureError = ctx.getString(R.string.assets_capture_cancelled)
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
            captureError = ctx.getString(R.string.assets_camera_permission)
        }
    }
    // 相册图片 / 相册视频（GetContent 免存储权限）
    val albumImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadVia(it, ctx.getString(R.string.assets_kind_image), ctx.getString(R.string.assets_album_image_btn), isVideo = false) }
    }
    val albumVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadVia(it, ctx.getString(R.string.assets_kind_video), ctx.getString(R.string.assets_album_video_btn), isVideo = true) }
    }

    fun startCapture() {
        captureError = null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            val u = captureUri()
            pendingCaptureUri = u
            runCatching { cameraLauncher.launch(u) }
                .onFailure { captureError = ctx.getString(R.string.assets_camera_failed, it.message ?: it.javaClass.simpleName) }
        }
    }

    // 资产点击预览：有 fileUri/remoteUrl（本地或已生成）时弹大图
    fun previewTarget(a: AssetEntity): String? = a.fileUri ?: a.remoteUrl

    /**
     * 图生图参考图（i2i）接线：资产可挂一张参考图（本地上传资产 fileUri 或相册新选图），
     * 重生成/审计重试时编排层将其作为 referenceUri（input_image）传入图像通道。
     * refTargetId 非空 → 弹「选择参考图来源」对话框（本地资产 或 相册选图）。
     */
    var refTargetId by remember { mutableStateOf<String?>(null) }
    val refImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val targetId = refTargetId
        refTargetId = null
        if (uri != null && targetId != null) {
            uploadScope.launch {
                val internal = AssetFiles.copyToInternal(ctx, uri, isVideo = false)
                if (internal == null) {
                    captureError = ctx.getString(R.string.assets_ref_read_fail)
                    return@launch
                }
                graph.db.assetDao().setReferenceImage(targetId, internal, System.currentTimeMillis())
                snackbar.show(ctx.getString(R.string.assets_ref_set_snack))
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            PageHeader(title = stringResource(R.string.page_assets), subtitle = "${project.name} · ${stringResource(R.string.assets_subtitle_tail)}")
        }
        item(span = { GridItemSpan(2) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in kinds) {
                    DramaFilterChip(
                        selected = kindFilter == k,
                        onClick = { kindFilter = k },
                        label = {
                            if (k == "全部") Text(stringResource(R.string.assets_filter_all, assets.size))
                            else Text(stringResource(R.string.assets_filter_kind, stringResource(kindLabelRes(k)), assets.count { it.kind == k }))
                        },
                    )
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.assets_review_progress, keptCount, assets.size), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allKept) stringResource(R.string.assets_review_all_kept)
                        else stringResource(R.string.assets_review_process),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.db.assetDao().updateReviewState(assets.map { it.assetId }, "keep")
                                snackbar.show(ctx.getString(R.string.assets_all_kept_snack))
                            }
                        }, enabled = assets.isNotEmpty()) { Text(stringResource(R.string.assets_keep_all_btn)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val ep = eps.firstOrNull()
                                if (ep == null) {
                                    snackbar.show(ctx.getString(R.string.assets_no_episode))
                                } else {
                                    graph.db.episodeDao().setReviewPassed(ep.episodeId, true)
                                    snackbar.show(ctx.getString(R.string.assets_review_passed))
                                }
                            }
                        }, enabled = assets.isNotEmpty()) { Text(stringResource(R.string.assets_review_pass_btn)) }
                    }
                }
            }
        }

        // ---- 本地上传入口（对齐源工程第六轮：拍摄/相册图/相册视频） ----
        item(span = { GridItemSpan(2) }) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.assets_upload_title), style = MaterialTheme.typography.titleMedium)
                    captureError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconActionButton(
                            label = stringResource(R.string.assets_capture_btn),
                            icon = Icons.Filled.PhotoCamera,
                            onClick = { startCapture() },
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(
                            label = stringResource(R.string.assets_album_image_btn),
                            icon = Icons.Filled.PhotoLibrary,
                            onClick = { albumImageLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(
                            label = stringResource(R.string.assets_album_video_btn),
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
                    title = stringResource(R.string.assets_empty_title),
                    subtitle = stringResource(R.string.assets_empty_subtitle),
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
                                        stringResource(R.string.assets_img_fail),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${stringResource(kindLabelRes(a.kind))}${a.poseRole?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "G1=${a.g1State} · ${stringResource(R.string.assets_review_label)}${a.reviewState}",
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
                                snackbar.show(ctx.getString(R.string.assets_kept_snack, a.assetId.takeLast(6)))
                            }
                        }, enabled = a.reviewState != "keep") { Text(stringResource(R.string.assets_keep_btn)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.db.assetDao().updateReviewState(listOf(a.assetId), "regen")
                                snackbar.show(ctx.getString(R.string.assets_regen_snack, a.assetId.takeLast(6)))
                            }
                        }, enabled = a.reviewState != "regen") { Text(stringResource(R.string.assets_regen_btn)) }
                        OutlinedButton(onClick = { refTargetId = a.assetId }) {
                            Text(
                                if (a.referenceImageUri != null) stringResource(R.string.assets_ref_change_btn)
                                else stringResource(R.string.assets_ref_set_btn),
                            )
                        }
                    }
                    if (a.referenceImageUri != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.assets_ref_marked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    graph.db.assetDao().setReferenceImage(a.assetId, null, System.currentTimeMillis())
                                    snackbar.show(ctx.getString(R.string.assets_ref_cleared))
                                }
                            }) { Text(stringResource(R.string.settings_key_clear_btn), style = MaterialTheme.typography.labelSmall) }
                        }
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
                OutlinedButton(onClick = { previewAsset = null }) { Text(stringResource(R.string.common_close)) }
            },
            title = { Text(stringResource(kindLabelRes(pa.kind))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 已挂参考图：预览中同时展示参考图（本地资产预览 + i2i 确认）
                    if (pa.referenceImageUri != null) {
                        Text(stringResource(R.string.assets_ref_i2i), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        GlideImage(
                            model = pa.referenceImageUri,
                            contentDescription = stringResource(R.string.assets_ref_i2i),
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentScale = ContentScale.Fit,
                            loading = placeholder {},
                            failure = placeholder { Text(stringResource(R.string.assets_ref_load_fail), color = MaterialTheme.colorScheme.error) },
                        )
                    }
                    when {
                        model == null -> Text(stringResource(R.string.assets_no_preview), color = MaterialTheme.colorScheme.outline)
                        isVideo -> Column {
                            Text(pa.prompt.take(48), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.assets_video_asset, model.substringAfterLast('/')),
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
                                Text(stringResource(R.string.assets_img_fail), color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            },
        )
    }

    // ---- 参考图来源选择：从本地上传资产（fileUri）选一张，或相册新选 ----
    refTargetId?.let { targetId ->
        val target = assets.firstOrNull { it.assetId == targetId }
        // 本地上传资产（有 fileUri 的图片）可直接作为参考图来源
        val localPicks = assets.filter { it.fileUri != null && !it.fileUri!!.endsWith(".mp4") && it.assetId != targetId }
        AlertDialog(
            onDismissRequest = { refTargetId = null },
            title = { Text(stringResource(R.string.assets_ref_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.assets_ref_target, target?.prompt?.take(20) ?: targetId.takeLast(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (localPicks.isNotEmpty()) {
                        Text(stringResource(R.string.assets_ref_local_pick), style = MaterialTheme.typography.labelMedium)
                        // 最多展示 4 个，避免 Dialog 过高
                        localPicks.take(4).forEach { pick ->
                            OutlinedButton(
                                onClick = {
                                    refTargetId = null
                                    scope.launch {
                                        graph.db.assetDao().setReferenceImage(targetId, pick.fileUri, System.currentTimeMillis())
                                        snackbar.show(ctx.getString(R.string.assets_ref_pick_snack, pick.prompt.take(12)))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(pick.prompt.take(24), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } else {
                        Text(stringResource(R.string.assets_ref_no_local), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { refImageLauncher.launch("image/*") }) { Text(stringResource(R.string.assets_ref_album_btn)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { refTargetId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}