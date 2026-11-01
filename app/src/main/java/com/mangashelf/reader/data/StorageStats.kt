package com.mangashelf.reader.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * v1.5.9：设置页「存储空间」的数据来源。
 *
 * 和 [ImportBatch] 的「导入历史饼图」完全分开：那张图按**批次数/书册数**统计，
 * 这张按**字节**统计真实目录占用。两者数据源、单位、口径都不同，不要混用。
 *
 * 统计口径（全部落在应用私有目录，不需要任何权限）：
 * - source   `files/comics/<书>/source.pdf`（v1.5.3 起 PDF 书会留一份原件副本）
 * - pages    `files/comics/<书>/` 下除 `source.pdf` 与 `thumbs/` 之外的其余文件
 * - thumbs   `files/comics/<书>/thumbs/` 下的缩略图 `.webp`
 * - backup   `files/backup/` 自动/手动备份
 * - database `databases/` 下 Room 的库文件（含 -wal / -shm）
 * - other    `files/` 里除 comics / backup 之外的其余占用（崩溃日志、诊断包等）
 *
 * 三档不会重叠：`source.pdf` 只进 source，`thumbs/` 只进 thumbs，剩下才进 pages。
 * 之前那版把所有非目录文件都算进 pages，会把 PDF 原件误记成"漫画页面"，本版已修。
 *
 * 全部在 IO 线程跑，每扫 256 个文件 yield 一次，避免几千页时卡住别的协程。
 * 结果带 5 分钟进程内缓存，手动刷新会强制重扫。
 */
object StorageStats {

    /** PDF 原件副本的文件名，见 [ComicStorage.sourcePdf] */
    private const val SOURCE_PDF = "source.pdf"
    private const val THUMBS_DIR = "thumbs"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /** 分类占比的一档 */
    class Slice(val key: String, val label: String, val bytes: Long)

    /** 单本漫画的占用明细 */
    class BookStat(
        val bookId: Long,
        val title: String,
        val pageCount: Int,
        val createdAt: Long,
        /** 原文件：source.pdf 真实大小，或导入时记录的源大小 */
        val sourceBytes: Long,
        /** 拆分出来的页面图 */
        val pageBytes: Long,
        /** 缩略图 */
        val thumbBytes: Long,
        /** 原文件大小来自导入记录而不是实际副本（文件夹导入没有 source.pdf） */
        val sourceEstimated: Boolean,
        /** 目录不存在或读不了，数字可能不全 */
        val partial: Boolean
    ) {
        val cacheBytes: Long get() = pageBytes + thumbBytes
        val totalBytes: Long get() = sourceBytes + pageBytes + thumbBytes
    }

    class Sample(
        val slices: List<Slice>,
        val books: List<BookStat>,
        val freeBytes: Long,
        val totalBytes: Long,
        val at: Long
    ) {
        val usedBytes: Long get() = slices.sumOf { it.bytes }
        val booksTotalBytes: Long get() = books.sumOf { it.totalBytes }
    }

    @Volatile
    private var cache: Sample? = null

    @Volatile
    private var cacheAt: Long = 0L

    /** 缓存里还有效的结果；没有就返回 null（UI 可先显示这个再做一次全量） */
    fun cached(): Sample? {
        val s = cache ?: return null
        return if (System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) s else null
    }

    fun invalidate() {
        cache = null
        cacheAt = 0L
    }

    suspend fun collect(context: Context, books: List<Book>, force: Boolean = false): Sample =
        withContext(Dispatchers.IO) {
            if (!force) cached()?.let { return@withContext it }

            val filesDir = context.filesDir
            val comicsRoot = File(filesDir, "comics")
            val backupRoot = File(filesDir, "backup")
            val dbRoot = File(context.applicationInfo.dataDir, "databases")

            var scanned = 0
            var sourceTotal = 0L
            var pageTotal = 0L
            var thumbTotal = 0L

            // 局部 suspend 函数可以直接改外层的 var（Kotlin 允许捕获可变局部变量）
            suspend fun walk(dir: File, onFile: (File) -> Unit) {
                val stack = ArrayDeque<File>()
                stack.addLast(dir)
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
                    for (kid in kids) {
                        if (++scanned % 256 == 0) yield()
                        if (kid.isDirectory) stack.addLast(kid) else onFile(kid)
                    }
                }
            }

            val stats = ArrayList<BookStat>(books.size)
            for (book in books) {
                val dir = File(comicsRoot, book.folder)
                var src = 0L
                var pag = 0L
                var thm = 0L
                var partial = false

                if (!dir.isDirectory) {
                    partial = true
                } else {
                    val kids = runCatching { dir.listFiles() }.getOrNull()
                    if (kids == null) {
                        partial = true
                    } else {
                        for (kid in kids) {
                            if (++scanned % 256 == 0) yield()
                            if (kid.isDirectory) {
                                // 目前只有 thumbs/ 一个子目录；将来若多了别的子目录也算进缩略图档
                                if (kid.name == THUMBS_DIR) {
                                    walk(kid) { thm += it.length() }
                                } else {
                                    walk(kid) { thm += it.length() }
                                }
                            } else {
                                if (kid.name == SOURCE_PDF) src += kid.length()
                                else pag += kid.length()
                            }
                        }
                    }
                }

                // 没有 source.pdf（文件夹/图片导入）就退回导入时记录的原文件大小
                val estimated = src <= 0L && book.sourceSizeBytes > 0L
                val srcFinal = if (src > 0L) src else book.sourceSizeBytes

                sourceTotal += srcFinal
                pageTotal += pag
                thumbTotal += thm
                stats += BookStat(
                    bookId = book.id,
                    title = book.title,
                    pageCount = book.pageCount,
                    createdAt = book.createdAt,
                    sourceBytes = srcFinal,
                    pageBytes = pag,
                    thumbBytes = thm,
                    sourceEstimated = estimated,
                    partial = partial
                )
            }

            val backup = sumDir(backupRoot)
            val database = sumDir(dbRoot)
            val filesTotal = sumDir(filesDir)
            // other = files 里除 comics（source+pages+thumbs）与 backup 之外的其余东西
            val other = (filesTotal - sourceTotal - pageTotal - thumbTotal - backup)
                .coerceAtLeast(0L)

            val stat = runCatching { StatFs(filesDir.absolutePath) }.getOrNull()
            val free = stat?.availableBytes ?: 0L
            val total = stat?.totalBytes ?: 0L

            val sample = Sample(
                slices = listOf(
                    Slice("source", "原文件", sourceTotal),
                    Slice("pages", "漫画页面", pageTotal),
                    Slice("thumbs", "缩略图", thumbTotal),
                    Slice("database", "数据库", database),
                    Slice("backup", "备份", backup),
                    Slice("other", "其他", other)
                ),
                books = stats.sortedByDescending { it.totalBytes },
                freeBytes = free,
                totalBytes = total,
                at = System.currentTimeMillis()
            )
            cache = sample
            cacheAt = sample.at
            sample
        }

    private suspend fun sumDir(dir: File): Long {
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
