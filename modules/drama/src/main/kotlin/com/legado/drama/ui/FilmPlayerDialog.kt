package com.legado.drama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
 * 控制器：倍速循环（1.0x→1.25x→1.5x→2.0x）、上一段/下一段跳转、段位指示（第 x/y 段）、关闭。
 */
@Composable
fun FilmPlayerDialog(parts: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val speeds = remember { listOf(1f, 1.25f, 1.5f, 2f) }
    var speedIndex by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(0) }

    val player = remember(parts) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(parts.map { MediaItem.fromUri(File(it).toURI().toString()) })
            prepare()
            playWhenReady = true
        }
    }
    val listener = remember {
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex
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

            // 顶部控制条：上一段/段位指示/下一段（左），倍速/关闭（右）
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
                    modifier = Modifier.width(52.dp),
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
}

/** 倍速显示文案：1.0x / 1.25x / 1.5x / 2.0x（避免浮点精度尾巴） */
private fun speedLabel(speed: Float): String = when (speed) {
    1f -> "1.0x"
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    else -> "2.0x"
}