package com.legado.drama.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.legado.drama.R
import com.legado.drama.engine.queue.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 渲染前台服务（PRD F09：渲染主承载）。
 * 生命周期由「一键渲染整集」触发 startForegroundService；渲染完成/取消后 stopSelf。
 * 队列快照只读，不持有渲染协程（渲染由 DefaultRenderQueue 在 AppGraph.scope 中消费，
 * 本服务仅保证进程存活 + 通知栏进度）。
 */
class RenderForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.notify_started_title),
                getString(R.string.notify_started_text),
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_EPISODE_ID)?.let { episodeId ->
            // 由 DramaBridge 将 episodeId 交给 AppGraph 的渲染队列
            com.legado.drama.DramaBridge.onRenderStartRequested(episodeId)
            observeQueue(episodeId)
        }
        return START_STICKY
    }

    private fun observeQueue(episodeId: String) {
        val graph = com.legado.drama.AppGraph.get(this)
        scope.launch {
            graph.renderQueue.state.collectLatest { snap: QueueSnapshot ->
                val paused = snap.pausedReason?.let { getString(R.string.notify_paused, it) } ?: ""
                val text = buildString {
                    append(getString(R.string.notify_progress, snap.completed, snap.total, snap.failed, paused))
                    snap.lastMessage?.let { append(" · $it") }
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(getString(R.string.notify_channel_name), text))
                // 全部终态 → 停服务
                if (snap.pending == 0 && snap.submittedInFlight == 0 && snap.pausedReason == null && snap.total > 0 && snap.completed == snap.total) {
                    stopSelf()
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.legado.drama.ui.DramaMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "drama_render"
        private const val NOTIFICATION_ID = 0xDA11

        /** Intent extra：渲染目标剧集 id */
        const val EXTRA_EPISODE_ID = "extra_episode_id"

        fun start(context: Context, episodeId: String) {
            val intent = Intent(context, RenderForegroundService::class.java)
                .putExtra(EXTRA_EPISODE_ID, episodeId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}