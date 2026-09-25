package io.legado.app.api.controller

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupAES
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.model.BookCover
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.ui.book.read.config.highlight.HighlightRuleStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.core.content.edit

/**
 * Web端备份控制器
 * 提供一键备份功能，支持下载ZIP备份文件
 *
 * 独立于Backup.backupLocked实现，因为backupLocked会在完成后删除临时文件
 * 这里自行控制备份流程：ZIP打包到临时文件后以文件流返回，不在内存中持有整个压缩包
 */
object BackupController {

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class BackupItemInfo(
        val fileName: String,
        val displayName: String,
        val description: String,
        val count: Int,
        val size: Long,
    )

    data class BackupOverview(
        val fileName: String,
        val totalSize: Long,
        val createTime: Long,
        val items: List<BackupItemInfo>,
    )

    private data class BackupItemDef(
        val fileName: String,
        val displayName: String,
        val description: String,
        val counter: () -> Int,
    )

    private data class ConfigItemDef(
        val fileName: String,
        val displayName: String,
        val description: String,
    )

    /** Web备份专用临时目录 */
    private val webBackupPath: String by lazy {
        appCtx.filesDir.getFile("web_backup").createFolderIfNotExist().absolutePath
    }

    /** 进行中的备份任务；并发请求共享同一次备份，避免重复打包和临时目录被并发清理 */
    private var inFlight: InFlightBackup? = null

    /** 缓存最近一次备份的概览信息 */
    @Volatile
    private var cachedBackupOverview: BackupOverview? = null

    /**
     * 一次备份任务的共享状态，并发请求通过同一个latch等待同一次打包完成
     */
    private class InFlightBackup {
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)

        @Volatile
        var zipFile: File? = null
    }

    /**
     * 执行备份并返回ZIP文件
     * 同步等待打包完成后以文件流返回；已有备份进行中时共享该次备份而不是重新打包
     */
    fun backup(): NanoHTTPD.Response {
        val flight = synchronized(this) {
            inFlight ?: InFlightBackup().also {
                inFlight = it
                startBackup(it)
            }
        }

        //书籍缓存多时打包可能分钟级；600s内未完成先向浏览器返回超时，任务本身在后台继续，后续请求可共享其结果
        val completed = flight.latch.await(600, TimeUnit.SECONDS)

        if (!completed) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                GSON.toJson(ReturnData().setErrorMsg("备份超时，书籍缓存较多时耗时较长，请稍后重试")),
            )
        }

        val error = flight.error.get()
        if (error != null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                GSON.toJson(ReturnData().setErrorMsg("备份失败: ${error.message ?: "未知错误"}")),
            )
        }

        val zipFile = flight.zipFile
        if (zipFile != null && zipFile.exists() && zipFile.length() > 0) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/zip",
                zipFile.inputStream(),
                zipFile.length(),
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"backup.zip\"")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            "application/json",
            GSON.toJson(ReturnData().setErrorMsg("备份文件生成失败")),
        )
    }

    /**
     * 后台执行一次备份并回填任务状态
     * finally 中必须先在锁内清空inFlight再countDown：保证失败/成功后新请求都能开启新备份，
     * 且清空与置位持有同一把锁，避免出现永远等待的孤儿任务
     */
    private fun startBackup(flight: InFlightBackup) {
        backupScope.launch {
            try {
                flight.zipFile = executeWebBackup()
                if (flight.zipFile == null) {
                    flight.error.set(RuntimeException("ZIP打包失败"))
                } else {
                    cachedBackupOverview = generateBackupOverview()
                }
            } catch (e: Throwable) {
                flight.error.set(e)
            } finally {
                synchronized(this@BackupController) {
                    if (inFlight === flight) inFlight = null
                }
                flight.latch.countDown()
            }
        }
    }

    /**
     * 获取备份内容预览
     */
    fun getBackupPreview(): ReturnData {
        val returnData = ReturnData()
        return try {
            val overview = cachedBackupOverview ?: generateBackupOverview()
            returnData.setData(overview)
        } catch (e: Exception) {
            returnData.setErrorMsg("获取备份预览失败: ${e.message}")
        }
    }

    /**
     * 执行Web备份，返回打包好的ZIP临时文件
     * 独立于Backup.backupLocked，自行控制备份和打包流程；文件留待下次备份开始时清理
     */
    private suspend fun executeWebBackup(): File? {
        val aes = BackupAES()
        FileUtils.delete(webBackupPath)

        withContext(Dispatchers.IO) {
            // 导出数据库数据到JSON文件
            writeListToJson(appDb.bookDao.all, "bookshelf.json", webBackupPath)
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", webBackupPath)
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", webBackupPath)
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", webBackupPath)
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", webBackupPath)
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", webBackupPath)
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", webBackupPath)
            FileUtils.createFileIfNotExist(webBackupPath + File.separator + HighlightRuleStore.backupFileName)
                .writeText(GSON.toJson(HighlightRuleStore.backupData(appCtx)))
            writeListToJson(Backup.mergeReadRecordsForLegacyCompat(appDb.readRecordDao.all), "readRecord.json", webBackupPath) // 导出readRecord.json 进行备份时，将相同 bookName 的多条记录合并为一条记录，readTime 取 SUM，bookAuthor 取非空值。这样 原始项目恢复 Max 备份后阅读时长计算正确
            writeListToJson(appDb.readRecordDao.getAllDetailsList(), "readRecordDetail.json", webBackupPath)
            writeListToJson(appDb.readRecordDao.getAllSessionsList(), "readRecordSession.json", webBackupPath)
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", webBackupPath)
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", webBackupPath)
            writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", webBackupPath)
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", webBackupPath)
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", webBackupPath)

            // 服务器配置加密存储
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(webBackupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }

            // 导出阅读配置
            GSON.toJson(ReadBookConfig.getBackupConfigList()).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            GSON.toJson(ReadBookConfig.getBackupShareConfig()).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }

            // 导出主题配置
            GSON.toJson(ThemeConfig.configList).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ThemeConfig.configFileName)
                    .writeText(it)
            }

            // 导出直链上传配置
            DirectLinkUpload.getConfig()?.let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + DirectLinkUpload.ruleFileName)
                    .writeText(GSON.toJson(it))
            }

            // 导出封面规则配置
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }

            // 导出SharedPreferences配置
            appCtx.getSharedPreferences(webBackupPath, "config")?.let { sp ->
                val edit = sp.edit()
                appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                    when (key) {
                        PreferKey.webDavPassword -> {
                            edit.putString(
                                key,
                                aes.runCatching {
                                    encryptBase64(value.toString())
                                }.getOrDefault(value.toString()),
                            )
                        }
                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
                edit.commit()
            }

            // 导出视频播放配置
            appCtx.getSharedPreferences(webBackupPath, "videoConfig")?.let { sp ->
                sp.edit(commit = true) {
                    appCtx.getSharedPreferences(VIDEO_PREF_NAME, android.content.Context.MODE_PRIVATE).all.forEach { (key, value) ->
                        when (value) {
                            is Int -> putInt(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }
            }

            Backup.stageBackgroundImageFiles(webBackupPath)
            Backup.stageHighlightRuleBackgroundFiles(webBackupPath)
            Backup.stageBookCache(webBackupPath)
            Backup.stageBookChapterForCache(webBackupPath)
        }

        // 打包ZIP
        val backupDir = File(webBackupPath)
        val files = backupDir.listFiles()?.toList() ?: return null
        if (files.isEmpty()) return null

        val paths = files.map { it.absolutePath }
        val tempZip = File(appCtx.externalFiles.absolutePath, "web_backup_tmp.zip")
        FileUtils.delete(tempZip)

        if (ZipUtils.zipFiles(paths, tempZip.absolutePath)) {
            return tempZip
        }

        return null
    }

    /**
     * 将列表数据写入JSON文件
     */
    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        withContext(Dispatchers.IO) {
            val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, list)
            }
        }
    }

    /**
     * 生成备份概览信息
     */
    private fun generateBackupOverview(): BackupOverview {
        val items = mutableListOf<BackupItemInfo>()
        var totalSize = 0L

        val backupItems = listOf(
            BackupItemDef(HighlightRuleStore.backupFileName, "高亮规则", "阅读高亮规则和分组配置") {
                HighlightRuleStore.load(appCtx).size
            },
            BackupItemDef("bookshelf.json", "书架书籍", "书架上的所有书籍信息") {
                appDb.bookDao.allBookCount
            },
            BackupItemDef("bookmark.json", "书签", "书籍阅读书签") {
                appDb.bookmarkDao.count
            },
            BackupItemDef("bookGroup.json", "书籍分组", "书架分组信息") {
                appDb.bookGroupDao.count
            },
            BackupItemDef("bookSource.json", "书源", "网络小说书源") {
                appDb.bookSourceDao.allCount()
            },
            BackupItemDef("rssSources.json", "订阅源", "订阅源") {
                appDb.rssSourceDao.size
            },
            BackupItemDef("rssStar.json", "订阅收藏", "订阅收藏内容") {
                appDb.rssStarDao.count
            },
            BackupItemDef("replaceRule.json", "替换规则", "正文替换净化规则") {
                appDb.replaceRuleDao.count
            },
            BackupItemDef("readRecord.json", "阅读记录", "阅读时长统计记录") {
                appDb.readRecordDao.count
            },
            BackupItemDef("readRecordDetail.json", "阅读详情", "每本书每天的阅读统计") {
                appDb.readRecordDao.getDetailsCount()
            },
            BackupItemDef("readRecordSession.json", "阅读时段", "时间线视图的阅读会话记录") {
                appDb.readRecordDao.getSessionsCount()
            },
            BackupItemDef("searchHistory.json", "搜索历史", "搜索关键词历史") {
                appDb.searchKeywordDao.count
            },
            BackupItemDef("txtTocRule.json", "TXT目录规则", "本地TXT目录解析规则") {
                appDb.txtTocRuleDao.count
            },
            BackupItemDef("httpTTS.json", "TTS配置", "在线朗读引擎配置") {
                appDb.httpTTSDao.count
            },
            BackupItemDef("keyboardAssists.json", "键盘辅助", "键盘快捷输入配置") {
                appDb.keyboardAssistsDao.count
            },
            BackupItemDef("dictRule.json", "词典规则", "长按查词规则") {
                appDb.dictRuleDao.count
            },
            BackupItemDef("servers.json", "服务器配置", "远程服务器配置（加密）") {
                appDb.serverDao.count
            },
            BackupItemDef("runtimeSourceCache.json", "书源运行数据", "书源登录信息和运行变量") {
                appDb.cacheDao.getRuntimeSourceCacheCount(System.currentTimeMillis())
            },
        )

        backupItems.forEach { item ->
            val count = item.counter()
            val file = File(webBackupPath, item.fileName)
            val size = if (file.exists()) file.length() else 0L
            totalSize += size

            items.add(
                BackupItemInfo(
                    fileName = item.fileName,
                    displayName = item.displayName,
                    description = item.description,
                    count = count,
                    size = size,
                ),
            )
        }

        // 书籍缓存
        val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
        if (selectedBooks.isNotEmpty()) {
            val cacheDir = File(io.legado.app.help.book.BookHelp.cachePath)
            var bookCacheSize = 0L
            var chapterCount = 0
            if (cacheDir.exists()) {
                selectedBooks.forEach { book ->
                    val folderName = book.getFolderName()
                    val bookFolder = File(cacheDir, folderName)
                    if (bookFolder.exists()) {
                        bookCacheSize += bookFolder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        chapterCount += appDb.bookChapterDao.getChapterCount(book.bookUrl)
                    }
                }
            }
            val indexEstimatedSize = selectedBooks.size * 300L
            val chapterEstimatedSize = chapterCount * 200L
            totalSize += bookCacheSize + indexEstimatedSize + chapterEstimatedSize
            items.add(
                BackupItemInfo(
                    fileName = "book_cache",
                    displayName = "书籍缓存",
                    description = "已缓存的章节内容文件",
                    count = selectedBooks.size,
                    size = bookCacheSize,
                ),
            )
            items.add(
                BackupItemInfo(
                    fileName = "bookCacheIndex.json",
                    displayName = "书籍缓存索引",
                    description = "缓存文件的索引信息",
                    count = selectedBooks.size,
                    size = indexEstimatedSize,
                ),
            )
            items.add(
                BackupItemInfo(
                    fileName = "bookChapterCache.json",
                    displayName = "书籍章节目录",
                    description = "缓存书籍的章节目录数据",
                    count = chapterCount,
                    size = chapterEstimatedSize,
                ),
            )
        }

        val configItems = listOf(
            ConfigItemDef(ReadBookConfig.configFileName, "阅读样式配置", "阅读界面样式配置"),
            ConfigItemDef(ReadBookConfig.shareConfigFileName, "共享阅读配置", "跨设备共享的阅读配置"),
            ConfigItemDef(ThemeConfig.configFileName, "主题配置", "界面主题样式配置"),
            ConfigItemDef(BookCover.configFileName, "封面规则", "自定义封面生成规则"),
            ConfigItemDef(DirectLinkUpload.ruleFileName, "直链上传配置", "直链上传规则配置"),
            ConfigItemDef("config.xml", "应用设置", "应用程序偏好设置"),
            ConfigItemDef("videoConfig.xml", "视频配置", "视频播放器设置"),
        )

        configItems.forEach { item ->
            val file = File(webBackupPath, item.fileName)
            if (file.exists()) {
                totalSize += file.length()
                items.add(
                    BackupItemInfo(
                        fileName = item.fileName,
                        displayName = item.displayName,
                        description = item.description,
                        count = 1,
                        size = file.length(),
                    ),
                )
            }
        }

        val bgFiles = Backup.getBackgroundImageFiles()
        val bgSize = bgFiles.sumOf { it.length() }
        if (bgFiles.isNotEmpty()) {
            totalSize += bgSize
            items.add(
                BackupItemInfo(
                    fileName = "readConfigBgImages",
                    displayName = "背景图片",
                    description = "阅读背景使用的自定义图片文件",
                    count = bgFiles.size,
                    size = bgSize,
                ),
            )
        }

        val highlightRuleBgFiles = HighlightRuleStore.getUsedBgImageFiles(appCtx)
        val highlightRuleBgSize = highlightRuleBgFiles.sumOf { it.length() }
        if (highlightRuleBgFiles.isNotEmpty()) {
            totalSize += highlightRuleBgSize
            items.add(
                BackupItemInfo(
                    fileName = HighlightRuleStore.backupBgDirName,
                    displayName = "高亮背景图片",
                    description = "高亮规则使用的自定义背景图片",
                    count = highlightRuleBgFiles.size,
                    size = highlightRuleBgSize,
                ),
            )
        }

        return BackupOverview(
            fileName = "backup.zip",
            totalSize = totalSize,
            createTime = System.currentTimeMillis(),
            items = items.filter { it.count > 0 || it.size > 0 },
        )
    }
}
