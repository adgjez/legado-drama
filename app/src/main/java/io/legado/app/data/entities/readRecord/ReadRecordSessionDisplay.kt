package io.legado.app.data.entities.readRecord

/**
 * 时间线上展示的一条阅读时段（由多条碎片会话按 20 分钟阈值合并而来）。
 * @property session 合并后的时段实体，start/end 为区间端点（包含中间的暂停间隙），
 *   并保留首个碎片的 id，便于需要 id 的删除等操作
 * @property readTime 该时段内的真实阅读时长（各碎片时长之和，不含合并间隙）。
 *   行时长必须用 readTime，不能用 (end - start)，否则行时长之和会远超日合计/总时长
 */
data class ReadRecordSessionDisplay(
    val session: ReadRecordSession,
    val readTime: Long
)
