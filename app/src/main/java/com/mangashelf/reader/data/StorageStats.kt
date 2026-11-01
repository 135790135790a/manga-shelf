package com.mangashelf.reader.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * v1.5.9：设置页「存储空间」饼图的数据来源。
 *
 * 和 [ImportBatch] 的「导入历史饼图」完全分开：那张图按**批次数/书册数**统计，
 * 这张按**字节**统计真实目录占用。两者数据源、单位、口径都不同，不要混用。
 *
 * 统计口径（全部落在应用私有目录，不需要任何权限）：
 * - pages    `files/comics/<书>/` 下的正片 `page_*.jpg`
 * - thumbs   `files/comics/<书>/thumbs/` 下的缩略图 `.webp`
 * - backup   `files/backup/` 自动/手动备份的 zip 或 db
 * - database `databases/` 下 Room 的库文件（含 -wal / -shm）
 * - other    files 目录里除上面几项之外的其余占用（崩溃日志、诊断包等）
 *
 * 全部在 IO 线程跑，每扫 256 个文件 yield 一次，避免几千页时卡住别的协程。
 */
object StorageStats {

    class Slice(val key: String, val label: String, val bytes: Long)

    class Sample(
        val slices: List<Slice>,
        val freeBytes: Long,
        val totalBytes: Long,
        val at: Long
    ) {
        val usedBytes: Long get() = slices.sumOf { it.bytes }
    }

    suspend fun collect(context: Context): Sample = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val comicsRoot = File(filesDir, "comics")
        val backupRoot = File(filesDir, "backup")
        val dbRoot = File(context.applicationInfo.dataDir, "databases")

        var pages = 0L
        var thumbs = 0L
        var scanned = 0

        val books = runCatching { comicsRoot.listFiles() }.getOrNull().orEmpty()
        for (book in books) {
            if (!book.isDirectory) {
                pages += book.length()
                continue
            }
            val children = runCatching { book.listFiles() }.getOrNull().orEmpty()
            for (child in children) {
                if (++scanned % 256 == 0) yield()
                if (child.isDirectory) {
                    thumbs += dirSize(child)
                } else {
                    pages += child.length()
                }
            }
        }

        val backup = dirSize(backupRoot)
        val database = dirSize(dbRoot)
        val filesTotal = dirSize(filesDir)
        // other = files 里除 comics / backup 之外的其余东西（崩溃日志、诊断包、导出物）
        val other = (filesTotal - pages - thumbs - backup).coerceAtLeast(0L)

        val stat = runCatching { StatFs(filesDir.absolutePath) }.getOrNull()
        val free = stat?.availableBytes ?: 0L
        val total = stat?.totalBytes ?: 0L

        Sample(
            slices = listOf(
                Slice("pages", "漫画页面", pages),
                Slice("thumbs", "缩略图", thumbs),
                Slice("database", "数据库", database),
                Slice("backup", "备份", backup),
                Slice("other", "其他", other)
            ),
            freeBytes = free,
            totalBytes = total,
            at = System.currentTimeMillis()
        )
    }

    private suspend fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        var scanned = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (kid in kids) {
                if (++scanned % 256 == 0) yield()
                if (kid.isDirectory) stack.addLast(kid) else sum += kid.length()
            }
        }
        return sum
    }
}
