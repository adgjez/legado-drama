package com.legado.drama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.legado.drama.R
import java.io.File

/**
 * 分段成片连续播放器：全屏 Dialog 内以 ExoPlayer 按 partsUrisJson 顺序连续播放全部分段。
 * 仅接收已确认存在且非空的本地 mp4 路径；播放列表顺序即传入顺序（调用方保证 shot_no 升序）。
 *
 * 控制器：倍速循环（1.0x→1.25x→1.5x→2.0x）、上一段/下一段跳转、点击段位指示弹出选集列表（任意跳段）、关闭。
 */
@Composable
fun FilmPlayerDialog(parts: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val speeds = remember { listOf(1f, 1.25f, 1.5f, 2f) }
    var speedIndex by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var showSegments by remember { mutableStateOf(false) }

    val player = remember(parts) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(parts.map { MediaItem.fromUri(File(it).toURI().toString()) })
            prepare()
            playWhenReady = true
        }
    }
    // 保持关闭回调最新引用，避免 listener 闭包过期导致播放器无法关闭
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val listener = remember {
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 全部分段播完（含拖到末尾触发）→ 自动关闭回到成片库
                if (playbackState == Player.STATE_ENDED) currentOnDismiss()
            }
        }
    }
    DisposableEffect(player, listener) {
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部控制条：上一段/段位指示（点选集）/下一段（左），倍速/关闭（右）
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.seekTo(maxOf(0, currentIndex - 1), 0) }) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.film_player_prev_seg),
                        tint = Color.White,
                    )
                }
                Text(
                    stringResource(R.string.film_player_seg_indicator, currentIndex + 1, parts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .width(52.dp)
                        .clickable { showSegments = true },
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { player.seekTo(minOf(parts.lastIndex, currentIndex + 1), 0) }) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.film_player_next_seg),
                        tint = Color.White,
                    )
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    speedIndex = (speedIndex + 1) % speeds.size
                    player.setPlaybackSpeed(speeds[speedIndex])
                }) {
                    Text(
                        speedLabel(speeds[speedIndex]),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }

    if (showSegments) {
        SegmentsPickerDialog(
            parts = parts,
            currentIndex = currentIndex,
            onPick = { index ->
                player.seekTo(index, 0)
                showSegments = false
            },
            onDismiss = { showSegments = false },
        )
    }
}

/** 选集弹窗：列出全部分段，点击任意段跳转；当前段高亮并标注。 */
@Composable
private fun SegmentsPickerDialog(
    parts: List<String>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.film_player_seg_pick_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                itemsIndexed(parts) { index, path ->
                    val file = File(path)
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                R.string.library_segment_row,
                                index + 1,
                                file.name,
                                if (file.exists() && file.length() > 0) file.length() / 1024 else 0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.film_player_seg_current),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/** 倍速显示文案：1.0x / 1.25x / 1.5x / 2.0x（避免浮点精度尾巴） */
private fun speedLabel(speed: Float): String = when (speed) {
    1f -> "1.0x"
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    else -> "2.0x"
}