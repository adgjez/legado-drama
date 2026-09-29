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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.R
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = stringResource(R.string.page_episodes), subtitle = projectName ?: projectId)

        if (episodes.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = stringResource(R.string.episodes_empty_title),
                subtitle = stringResource(R.string.episodes_empty_subtitle),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes, key = { it.episodeId }) { ep: EpisodeEntity ->
                    DramaCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(stringResource(R.string.episode_title, ep.epNo), style = MaterialTheme.typography.titleMedium)
                                val chars = ep.scriptJson?.length ?: 0
                                Text(
                                    if (chars > 0) {
                                        stringResource(
                                            R.string.episode_script_ok,
                                            chars,
                                            if (ep.reviewPassed) stringResource(R.string.episode_reviewed) else "",
                                        )
                                    } else stringResource(R.string.episode_no_script),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Button(onClick = { onOpenEpisode(ep.episodeId) }) { Text(stringResource(R.string.common_enter)) }
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
                        snackbar.show(context.getString(R.string.episode_add_hint))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.episode_add_btn)) }
        }
    }
}