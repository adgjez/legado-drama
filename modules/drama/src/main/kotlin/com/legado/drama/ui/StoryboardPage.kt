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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.data.entity.ShotEntity
import com.legado.drama.service.RenderForegroundService
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.HeroButton
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import com.legado.drama.ui.components.PrimaryButton
import kotlinx.coroutines.launch

/**
 * 分镜页（S5，对齐源工程 StoryboardPage 结构）：
 * 剧本原文折叠卡 + 每镜状态条（六铁律校验 sb_check）+ 渲染本集入口。
 * legado 分镜由 AI 流水线自动生成（五阶段 ④生成分镜），本页只做查看与出队复核。
 */
@Composable
fun StoryboardPage(graph: AppGraph, episodeId: String) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    val shots by graph.db.shotDao().observeByEpisode(episodeId).collectAsState(initial = emptyList())
    val snackbar = LocalDramaSnackbar.current
    var scriptText by remember { mutableStateOf<String?>(null) }
    var showScript by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }

    LaunchedEffect(episodeId) {
        scriptText = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                graph.db.episodeDao().get(episodeId)?.scriptJson
            }
        }.getOrNull()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "分镜", subtitle = "第${episodeId.substringAfterLast("ep", "?")}集 · 六铁律校验状态")
        }

        // 剧本原文折叠卡（对齐源工程：进入分镜页即加载本集剧本，折叠展示）
        scriptText?.let { script ->
            if (script.isNotBlank()) {
                item {
                    DramaCard(
                        onClick = { showScript = !showScript },
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Text("剧本原文（${script.length}字）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (showScript) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (showScript) {
                                Text(
                                    script,
                                    Modifier.padding(top = 8.dp).height(240.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            HeroButton(
                text = if (queued) "已入队…" else "渲染本集（六铁律复核）",
                onClick = {
                    scope.launch {
                        val ep = graph.db.episodeDao().get(episodeId)
                        if (ep == null) {
                            snackbar.show("剧集不存在，请回项目页重新进入")
                        } else {
                            RenderForegroundService.start(graph.appContext, episodeId)
                            queued = true
                            snackbar.show("已入队渲染：$episodeId")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (queued) {
                Text("渲染进度见「渲染」标签页；六铁律在出队时逐镜复核，不合规镜头会被 BLOCKED 并给出原因。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        if (shots.isEmpty()) {
            item {
                EmptyState(
                    icon = { Icon(Icons.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有分镜",
                    subtitle = "AI 一键成片会自动拆解镜头。回「项目」页用 AI 一键成片生成后，分镜将出现在这里。",
                )
            }
        }
        items(shots, key = { it.shotId }) { shot: ShotEntity ->
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${shot.shotNo}", style = MaterialTheme.typography.titleSmall)
                        ShotCheckStatus(shot.sbCheck)
                    }
                    shot.action?.let { Text("动作：$it", style = MaterialTheme.typography.bodyMedium) }
                    shot.dialogue?.let { Text("台词：「$it」", style = MaterialTheme.typography.bodySmall) }
                    shot.narration?.let { Text("旁白：$it", style = MaterialTheme.typography.bodySmall) }
                    shot.carryOver?.let { Text("承接：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    Text("引用资产：${shot.firstAssetIds}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/** 分镜校验状态条（对齐源工程 StatusChip：pass=CheckCircle / pending=DateRange / error=Warning） */
@Composable
private fun ShotCheckStatus(sbCheck: String) {
    val (icon, text, tint) = when {
        sbCheck == "pass" -> Triple(
            Icons.Filled.CheckCircle, "校验通过", MaterialTheme.colorScheme.primary,
        )
        sbCheck == "pending" -> Triple(
            Icons.Filled.DateRange, "待生成", MaterialTheme.colorScheme.outline,
        )
        else -> Triple(
            Icons.Filled.Warning, "校验失败（$sbCheck）", MaterialTheme.colorScheme.error,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}