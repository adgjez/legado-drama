package io.legado.app.data.entities.readRecord

/**
 * 按天聚合的时间线数据。
 * @property readTime 当天真实阅读时长（未合并会话的时长之和，不含合并展示时段时的暂停间隙）。
 * 展示用 sessions 是按 20 分钟阈值合并后的阅读时段，端点跨度包含间隙，
 * 因此日合计必须用 readTime，不能对合并后的 sessions 求 (end - start) 之和，
 * 否则日合计会远超该书总阅读时长。
 */
data class ReadRecordTimelineDay(
    val date: String,
    val sessions: List<ReadRecordSessionDisplay>,
    val readTime: Long = 0
)
