package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.globalExecutor

/**
 * 阅读会话缓冲器（单例）。
 *
 * 背景：旧实现每次翻页/暂停都立即写一条 ReadRecordSession，高频翻页会产生海量碎片
 * （重度使用一个月可达数万条）。本类在内存中维护一条"打开中的会话"，只在会话结束
 * （暂停/退出/换书）或跨度过长时才落库，从源头消除碎片。
 *
 * 线程模型：tick/flush 由主线程调用（synchronized 保护），
 * 实际写库投递到 globalExecutor 串行执行，保证多条会话按时间顺序入库。
 */
object ReadSessionRecorder {

    /**
     * 单条会话的最大跨度。超过后立即落库并从当前结束点续开新会话：
     * 1. 限制进程异常退出时的最大丢失时长；
     * 2. 会话章节名跟随"最后读到的章节"，限长可让时间线上的章节名保持粒度。
     */
    private const val MAX_SESSION_SPAN = 10 * 60 * 1000L

    private val repository by lazy { ReadRecordRepository(appDb.readRecordDao) }

    private var openSession: ReadRecordSession? = null

    /**
     * 阅读开始/恢复。同一本书已有未落库会话时延续它（落库失败重试场景），
     * 否则开启新会话。
     */
    @Synchronized
    fun onReadStart(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        chapterTitle: String,
        now: Long = System.currentTimeMillis()
    ) {
        val open = openSession
        if (open != null && isSameBook(open, deviceId, bookName, bookAuthor)) {
            return
        }
        if (open != null) {
            saveLocked(open)
        }
        openSession = ReadRecordSession(
            deviceId = deviceId,
            bookName = bookName,
            bookAuthor = bookAuthor,
            startTime = now,
            endTime = now,
            durChapterTitle = chapterTitle
        )
    }

    /**
     * 阅读进行中（翻页/章节切换等）：延长当前会话并刷新章节名。
     * 会话跨度过长时先落库，再从旧会话结束点续开新会话（保持墙钟时间连续，不丢翻页间隙）。
     */
    @Synchronized
    fun onReadTick(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        chapterTitle: String,
        now: Long = System.currentTimeMillis()
    ) {
        var open = openSession
        if (open == null || !isSameBook(open, deviceId, bookName, bookAuthor)) {
            if (open != null) {
                saveLocked(open)
            }
            open = ReadRecordSession(
                deviceId = deviceId,
                bookName = bookName,
                bookAuthor = bookAuthor,
                startTime = now,
                endTime = now,
                durChapterTitle = chapterTitle
            )
            openSession = open
        }
        if (now - open.startTime >= MAX_SESSION_SPAN) {
            saveLocked(open)
            open = open.copy(id = 0, startTime = open.endTime, endTime = open.endTime)
            openSession = open
        }
        openSession = open.copy(
            endTime = maxOf(open.endTime, now),
            durChapterTitle = chapterTitle.ifBlank { open.durChapterTitle }
        )
    }

    /** 阅读暂停/退出/换书：落库当前会话。 */
    @Synchronized
    fun flush() {
        val open = openSession ?: return
        openSession = null
        saveLocked(open)
    }

    private fun isSameBook(
        session: ReadRecordSession,
        deviceId: String,
        bookName: String,
        bookAuthor: String
    ): Boolean {
        return session.deviceId == deviceId &&
            session.bookName == bookName &&
            session.bookAuthor == bookAuthor
    }

    private fun saveLocked(session: ReadRecordSession) {
        // 无有效时长的空会话直接丢弃，避免产生 0 秒碎片
        if (session.endTime - session.startTime <= 0L && session.words <= 0L) {
            return
        }
        globalExecutor.execute {
            try {
                kotlinx.coroutines.runBlocking {
                    repository.saveReadSession(session)
                }
            } catch (e: Exception) {
                AppLog.put("保存阅读会话失败", e, true)
            }
        }
    }
}
