package io.legado.app.ui.rss.article

import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource

/**
 * RSS 分类/排序页数据访问接口
 * 经 [RssSortViewModel] 构造注入（默认 Default 实现），JVM 单测可直接 Fake（照 RecycleBinRepository 模式）
 */
interface RssSortRepository {

    fun getSourceByKey(key: String): RssSource?

    fun updateSource(source: RssSource)

    fun deleteArticles(sourceUrl: String)

    fun getRecords(origin: String?): List<RssReadRecord>

    fun countRecords(origin: String?): Int

    fun deleteRecords(origin: String?)

    companion object Default : RssSortRepository {

        override fun getSourceByKey(key: String): RssSource? = appDb.rssSourceDao.getByKey(key)

        override fun updateSource(source: RssSource) {
            appDb.rssSourceDao.update(source)
        }

        override fun deleteArticles(sourceUrl: String) {
            appDb.rssArticleDao.delete(sourceUrl)
        }

        override fun getRecords(origin: String?): List<RssReadRecord> =
            origin?.let { appDb.rssReadRecordDao.getRecordsByOrigin(it) }
                ?: appDb.rssReadRecordDao.getRecords()

        override fun countRecords(origin: String?): Int =
            origin?.let { appDb.rssReadRecordDao.countRecordsByOrigin(it) }
                ?: appDb.rssReadRecordDao.countRecords

        override fun deleteRecords(origin: String?) {
            if (origin == null) {
                appDb.rssReadRecordDao.deleteAllRecord()
            } else {
                appDb.rssReadRecordDao.deleteRecordsByOrigin(origin)
            }
        }
    }
}