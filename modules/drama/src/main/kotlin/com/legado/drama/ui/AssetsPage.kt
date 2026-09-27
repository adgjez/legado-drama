package com.legado.drama.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.DramaFilterChip
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.HeroButton
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import com.legado.drama.ui.components.PrimaryButton
import com.legado.drama.ui.components.statusErr
import com.legado.drama.ui.components.statusInfo
import com.legado.drama.ui.components.statusOk
import kotlinx.coroutines.launch

/** 资产 kind → 中文标签（对齐源工程资产类型筛选） */
private fun kindLabel(kind: String): String = when (kind) {
    "character" -> "角色"
    "scene" -> "场景"
    "prop" -> "道具"
    else -> kind
}

/**
 * 资产库页（S3/S4，对齐源工程 AssetsPage 网格结构）：
 * kind 筛选 chips + 双列资产网格 + 评审操作（F04 硬门槛：keep/regen、
 * 全部保留、标记评审通过后可渲染）。legado 资产由 AI 流水线自动生成，
 * 本页只负责审核与放行。
 */
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
}