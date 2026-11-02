package com.mangashelf.reader.data

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 图片导入工作栏的落盘规则。
 *
 * 与 [ComicStorage] 的关系：**平级但互不可见**。
 * 漫画在 `files/comics/<书>/page_001.jpg` + `source.pdf`，图片在 `files/gallery/<uuid>.jpg`。
 *
 * 图片一律平铺在 gallery 根下，归属靠数据库的 `folderId`，不做真实目录嵌套——
 * 移动图片 = 改一行数据，不会出现"复制到一半被杀"的半份文件。
 */
class GalleryStorage(context: Context) {

    private val root: File = File(context.filesDir, "gallery")

    private val thumbsRoot: File = File(root, "thumbs")

    /** 给新导入的图片分配文件名（扩展名沿用原图） */
    fun newRelPath(originalName: String): String {
        val ext = originalName.substringAfterLast('.', "jpg").lowercase(Locale.US)
            .let { if (it in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")) it else "jpg" }
        val base = UUID.randomUUID().toString().replace("-", "").take(16)
        return "$base.$ext"
    }

    fun fileOf(relPath: String): File {
        // relPath 只允许文件名，防止 `../` 之类的越界写入
        val safe = relPath.replace('\\', '/').substringAfterLast('/')
        return File(root, safe)
    }

    fun thumbFileOf(relPath: String): File {
        val base = relPath.substringAfterLast('/').substringBeforeLast('.')
        return File(thumbsRoot, "$base.webp")
    }

    fun deleteFile(relPath: String) {
        runCatching { fileOf(relPath).delete() }
        runCatching { thumbFileOf(relPath).delete() }
    }

    fun deleteThumb(relPath: String) {
        runCatching { thumbFileOf(relPath).delete() }
    }

    /** 整块占用（图片 + 缩略图），给存储统计的「图片工作栏」切片 */
    fun totalSize(): Long = runCatching {
        if (!root.isDirectory) 0L
        else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * 清理不属于任何一条记录的残留文件。
     *
     * 与 [ComicStorage.cleanupOrphans] 同理：导入中途被杀会留下写了一半的文件。
     * 只在启动时调用，导入进行中绝不能调。
     */
    fun cleanupOrphans(knownRelPaths: Set<String>): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        val knownThumbs = knownRelPaths.map {
            it.substringAfterLast('/').substringBeforeLast('.')
        }.toSet()
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in knownRelPaths) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        thumbsRoot.listFiles()?.forEach { file ->
            val base = file.name.substringBeforeLast('.')
            if (file.isFile && base !in knownThumbs) {
                runCatching { file.delete() }
            }
        }
        return removed
    }
}
