package com.mangashelf.reader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把书页导出到系统相册。
 *
 * 两条完全不同的写入路径：
 *  - Android 10+：MediaStore + RELATIVE_PATH，不需要任何权限
 *  - Android 9-：MediaStore 的 DATA 列写绝对路径，需要 WRITE_EXTERNAL_STORAGE
 *
 * 单张失败继续导后面的页，最后返回成功数——一张坏图不该让整本导出失败。
 */
object GalleryExporter {

    /** 导出时的相册子目录名（相册里会显示为「Pictures/MangaShelf」） */
    private const val ALBUM = "MangaShelf"

    suspend fun export(
        context: Context,
        title: String,
        files: List<File>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val safeTitle = sanitize(title)
        val resolver = context.contentResolver
        var saved = 0
        files.forEachIndexed { index, file ->
            onProgress(index, files.size)
            if (!file.exists() || file.length() == 0L) return@forEachIndexed
            val name = String.format(Locale.US, "%s_%03d.jpg", safeTitle, index + 1)
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES
                            ),
                            ALBUM
                        )
                        if (!dir.exists()) dir.mkdirs()
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("相册写入被拒绝")
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: error("相册写入被拒绝")
            }.onSuccess { saved++ }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && saved > 0) {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM
            )
            MediaScannerConnection.scanFile(
                context,
                dir.listFiles()?.map { it.absolutePath }?.toTypedArray() ?: emptyArray(),
                null,
                null
            )
        }
        onProgress(files.size, files.size)
        saved
    }

    /**
     * 把图片工作栏里的单张图存进相册，**保留原文件名与格式**（动图原样复制，不转码）。
     * 返回是否成功；失败只返回 false，不抛异常。
     */
    suspend fun saveOne(context: Context, file: File, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() == 0L) return@withContext false
            runCatching {
                val name = normalizeName(displayName, file.extension)
                val mime = mimeOf(name)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES
                            ),
                            ALBUM
                        )
                        if (!dir.exists()) dir.mkdirs()
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
                    }
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: error("相册写入被拒绝")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: error("相册写入被拒绝")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        ALBUM
                    )
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(File(dir, name).absolutePath),
                        null,
                        null
                    )
                }
            }.isSuccess
        }

    private fun normalizeName(raw: String, fallbackExt: String): String {
        val cleaned = sanitize(raw.substringBeforeLast('.', raw))
        val ext = raw.substringAfterLast('.', fallbackExt).lowercase(Locale.US)
        val safeExt = if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "apng"))
            ext else "jpg"
        return "$cleaned.$safeExt"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "png", "apng" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/jpeg"
    }

    /** 去掉文件名里的非法字符，避免相册条目建不出来 */
    private fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim()
        return cleaned.ifBlank { "漫画" }.take(60)
    }
}
