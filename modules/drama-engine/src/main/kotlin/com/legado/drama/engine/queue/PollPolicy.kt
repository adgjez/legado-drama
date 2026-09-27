package com.legado.drama.engine.queue

/**
 * 轮询自适应与取回上限（HANDOVER F10/P1-7、P0-FIX F5）：
 *  - 轮询自适应：submitted 后前 10 分钟每 30s 轮询一次，超过 10 分钟降至每 60s
 *    （PRD F09 / 架构 §7.1 承诺：后台 8 小时轮询流量 ≤5MB）；
 *  - fetch 取回上限：单镜已付费 clip 下载失败连续累计达到 FETCH_RETRY_MAX 后，
 *    本轮退出并保留 SUBMITTED（由下次 recoverOnBoot 经 pendingRepoll 再试一次），
 *    消除「永久空转」且不重复付费。
 * 纯逻辑，JVM 可单测；由队列宿主（Android 层 DefaultRenderQueue）按时间戳驱动。
 */
object PollPolicy {

    /** 前 10 分钟间隔 30s（毫秒） */
    const val EARLY_INTERVAL_MS = 30_000L

    /** 10 分钟后降为 60s（毫秒） */
    const val LATE_INTERVAL_MS = 60_000L

    /** 自适应切换点：10 分钟 */
    const val SLOWDOWN_AFTER_MS = 10 * 60_000L

    /** 取回失败重试上限（P0-FIX F5：达到上限退出本 repoll，保 submitted 待下次续传） */
    const val FETCH_RETRY_MAX = 8

    /**
     * 自适应轮询间隔：按「首次提交时间戳 → 当前时间」的距离选择 30s/60s。
     * submittedAtMs 为空（未知提交时间）按早期 30s 处理。
     */
    fun adaptivePollIntervalMs(submittedAtMs: Long?, nowMs: Long): Long {
        if (submittedAtMs == null) return EARLY_INTERVAL_MS
        val age = nowMs - submittedAtMs
        return if (age < SLOWDOWN_AFTER_MS) EARLY_INTERVAL_MS else LATE_INTERVAL_MS
    }

    /** 判断某轮取回失败是否已达上限：已失败 fetchFailCount 次，本次增加后 ≥ 上限 → true */
    fun shouldGiveUpAfterFetchFails(fetchFailCount: Int): Boolean = fetchFailCount >= FETCH_RETRY_MAX
}