package com.legado.drama.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地上传 URI 落盘（对齐源工程 AssetFiles）：统一拷贝到 filesDir/uploads/，
 * 返回稳定 file:// URI 供资产卡预览/图生图引用。
 *
 * 背景：相册 GetContent 返回 content:// URI 仅回调内临时可读（进程重启即失效）；
 * 拍摄输出在 cacheDir/capture/ 易被系统清理。拍照后/选图后立即拷贝到内部目录。
 */
object AssetFiles {
    const val UPLOAD_DIR = "uploads"

    /**
     * 将任意可读 URI（content:// / file://）拷贝到 filesDir/uploads/ 并返回 file:// URI 字符串。
     * @param isVideo 视频 true / 图片 false（决定 MIME 回退扩展名与文件名前缀）
     * @return 内部 file:// URI 字符串；读取失败/空文件返回 null
     */
    suspend fun copyToInternal(ctx: Context, uri: Uri, isVideo: Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.filesDir, UPLOAD_DIR).apply { mkdirs() }
                val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
                val ext = extFromMime(mime, fallback = if (isVideo) "mp4" else "jpg")
                val target = File(
                    dir,
                    internalFileName(type = if (isVideo) "video" else "image", ext = ext),
                )
                val ok = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                    target.length() > 0L   // 空文件视为失败（取消拍摄可能留下 0 字节文件）
                } ?: false
                if (ok) Uri.fromFile(target).toString() else null
            }.getOrNull()
        }

    /** MIME → 扩展名；无法识别时用 fallback（视频 mp4 / 图片 jpg） */
    fun extFromMime(mime: String?, fallback: String): String = when {
        mime == null -> fallback
        mime == "image/jpeg" || mime == "image/jpg" -> "jpg"
        mime == "image/png" -> "png"
        mime == "image/webp" -> "webp"
        mime == "video/mp4" -> "mp4"
        mime.startsWith("image/") -> "jpg"
        mime.startsWith("video/") -> "mp4"
        else -> fallback
    }

    /** 稳定文件名：类型前缀 + 时间戳 + 扩展名 */
    fun internalFileName(type: String, ext: String): String =
        "${type}_${System.currentTimeMillis()}.$ext"
}