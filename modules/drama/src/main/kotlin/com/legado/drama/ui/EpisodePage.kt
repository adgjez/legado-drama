package com.legado.drama.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.ui.components.DramaCard
import com.legado.drama.ui.components.EmptyState
import com.legado.drama.ui.components.HeroButton
import com.legado.drama.ui.components.LocalDramaSnackbar
import com.legado.drama.ui.components.PageHeader
import kotlinx.coroutines.launch

/**
 * 剧集列表页（S2 子页，对齐源工程 EpisodePage 结构）：
 * 项目内分集管理。列出该项目全部剧集，点击进入对应剧集的资产页。
 * legado 剧集由 AI 一键成片自动创建（ep_no 自增、剧本自动抽取），
 * 无需手动新增——空 UI 态给出引导文案。
 */
@Composable
fun EpisodePage(
    graph: AppGraph,
    projectId: String,
    projectName: String?,
    onOpenEpisode: (String) -> Unit,
) {
    val episodes by graph.db.episodeDao().observeByProject(projectId).collectAsState(initial = emptyList())
    val snackbar = LocalDramaSnackbar.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "剧集", subtitle = projectName ?: projectId)

        if (episodes.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "还没有剧集",
                subtitle = "项目内还没有剧集。请回到项目页用「AI 一键成片」导入剧本，AI 会自动分集并生成资产、分镜与渲染任务。",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes, key = { it.episodeId }) { ep: EpisodeEntity ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("第${ep.epNo}集", style = MaterialTheme.typography.titleMedium)
                                val chars = ep.scriptJson?.length ?: 0
                                Text(
                                    if (chars > 0) "剧本 ${chars}字${if (ep.reviewPassed) " · 评审已通过" else ""}"
                                    else "未导入剧本",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Button(onClick = { onOpenEpisode(ep.episodeId) }) { Text("进入") }
                        }
                    }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // 提供「一键成片追加集」提示：legado 建集入口在项目页 AI 一键成片
                        snackbar.show("新增剧集请回项目页使用「AI 一键成片」重新导入剧本")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("＋ 如何新增剧集") }
        }
    }
}