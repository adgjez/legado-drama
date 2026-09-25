package com.legado.drama.engine.queue

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

/**
 * 提交限速门（架构文档 §3 + §4.1，继承 pavo wait_video_submit_slot 语义）。
 * 视频提交前阻塞至距上次提交 ≥ intervalMs（默认 120_000，可配）。
 * 进程内互斥 + 全局时间戳，先等门再干活，杜绝并发烧配额。
 */
enum class ChannelKind { VIDEO, TEXT, IMAGE }

interface RateGate {
    /** 视频提交前阻塞至距上次提交 ≥ intervalMs（默认 120_000，可配）。进程内互斥+全局时间戳 */
    suspend fun awaitSlot(channel: ChannelKind = ChannelKind.VIDEO)
}

/**
 * 默认实现：Mutex 保护的单例时间戳（对应 pavo wait_video_submit_slot）；
 * 间隔默认 120s、可由设置页覆盖（非法值兜底 120）。三通道各自独立计时。
 */
class DefaultRateGate(
    private val videoIntervalMs: Long = DEFAULT_VIDEO_INTERVAL_MS,
    private val textIntervalMs: Long = 0L,
    private val imageIntervalMs: Long = 0L,
) : RateGate {

    private val mutex = Mutex()
    private val lastSubmitAt = mutableMapOf<ChannelKind, Long>()

    override suspend fun awaitSlot(channel: ChannelKind) {
        val interval = when (channel) {
            ChannelKind.VIDEO -> videoIntervalMs.coerceAtLeast(0L)
            ChannelKind.TEXT -> textIntervalMs.coerceAtLeast(0L)
            ChannelKind.IMAGE -> imageIntervalMs.coerceAtLeast(0L)
        }
        // 进程内互斥：同一时间只允许一个提交者在计算/等待
        mutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastSubmitAt[channel] ?: 0L
            val elapsed = now - last
            if (elapsed < interval) {
                delay(interval - elapsed)
            }
            lastSubmitAt[channel] = System.currentTimeMillis()
        }
    }

    companion object {
        /** 120s 提交限速门（架构文档 §4.1、PRD 继承 pavo） */
        const val DEFAULT_VIDEO_INTERVAL_MS = 120_000L

        /** 由用户设置项解析并兜底：非法值（<=0、非数字）一律回落默认 120s */
        fun parseInterval(raw: String?): Long {
            val v = raw?.trim()?.toLongOrNull() ?: return DEFAULT_VIDEO_INTERVAL_MS
            return if (v <= 0) DEFAULT_VIDEO_INTERVAL_MS else v
        }
    }
}

/** 渲染队列快照（通知栏与 UI 共用，StateFlow） */
data class QueueSnapshot(
    val episodeId: String? = null,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val blocked: Int = 0,
    val pending: Int = 0,
    val submittedInFlight: Int = 0,
    val etaMinutes: Int? = null,
    val pausedReason: String? = null,   // "budget" / "network" / "auth" / null
    val lastMessage: String? = null,
)

/**
 * 渲染队列（架构文档 §3 + §7）：
 * 单消费者协程，pending→submitted→completed/failed→blocked；
 * 入队前过 StoryboardGate + BudgetGuard + Key 有效。
 */
interface RenderQueue {
    val state: StateFlow<QueueSnapshot>
    /** 入队前过 StoryboardGate+BudgeGuard+Key有效 */
    suspend fun enqueueEpisode(episodeId: String)
    /** 弱网/预算超限/401 自动触发 */
    suspend fun pause(reason: String)
    /** 预算超限需 confirm */
    suspend fun resume(confirmedByUser: Boolean = false)
    fun cancelShot(shotId: String)
}