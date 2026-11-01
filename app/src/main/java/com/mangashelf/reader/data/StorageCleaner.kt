package com.mangashelf.reader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.5.9：设置页「按漫画查看」里的清理动作。
 *
 * 只删**可再生**的东西：
 * - `files/comics/<书>/thumbs/` 缩略图目录（[ThumbStore] 会在下次显示时按需重建）
 *
 * 不碰：
 * - `source.pdf` 原文件副本
 * - `page_*.jpg` 页面图（删了就得重新导入）
 *
 * 书架封面用的是 `page_*.jpg` 本身（`ComicStorage.coverPath`），不是缩略图，
 * 所以清完缩略图书架不会白屏，最多是页面管理页的缩略图重建一次。
 */
object StorageCleaner {

    class Result(val deletedBytes: Long, val ok: Boolean)

    /** 删除某一本书的缩略图目录，返回删除的字节数 */
    suspend fun clearThumbs(context: Context, folder: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(File(context.filesDir, "comics"), folder)
            val thumbs = File(dir, "thumbs")
            if (!thumbs.exists()) return@withContext Result(0L, true)
            val before = thumbs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val ok = thumbs.deleteRecursively()
            StorageStats.invalidate()
            Result(before, ok)
        }.getOrElse { Result(0L, false) }
    }
}
