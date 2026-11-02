package com.mangashelf.reader.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.ComicStorage
import com.mangashelf.reader.data.PageEntry
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 漫画导出。
 *
 * 四种格式共用同一套「按 [pages] 顺序、跳过缺失页、可取消、报进度」的骨架，
 * 差异只在最后一步怎么写：
 * - 原始 PDF：直接复制 `source.pdf`，不重新编码，最快无损
 * - 重排 PDF：用 [PdfDocument] 逐页画位图，跟随页面管理里的当前顺序与已删状态
 * - ZIP：逐页 copy 原始字节，不解码，几乎不占内存
 * - 长图：流式分片，峰值内存 ≈ 一片 + 一页（见 [MAX_SLICE_HEIGHT] 注释）
 *
 * 所有输出都走 SAF（调用方传目标目录的 [DocumentFile]），不写死路径，
 * 也不需要任何存储权限。
 */
object Exporter {

    enum class Format(val label: String, val ext: String, val mime: String) {
        PDF_ORIGINAL("原始 PDF", "pdf", "application/pdf"),
        PDF_REBUILT("重排 PDF", "pdf", "application/pdf"),
        ZIP("压缩包 ZIP", "zip", "application/zip"),
        LONG_IMAGE("长图", "jpg", "image/jpeg")
    }

    data class Progress(val phase: String, val current: Int, val total: Int)

    data class Result(
        val ok: Boolean,
        /** 生成的一个或多个文件（长图分片时会有多个） */
        val uris: List<Uri> = emptyList(),
        val message: String = ""
    )

    /** 单张长图最大高度：8000 × 1440 × 4B ≈ 46 MB，留足余量避免 OOM */
    const val MAX_SLICE_HEIGHT = 8000

    /** 长图统一宽度，与导入时的渲染宽度一致，不做二次放大 */
    const val MAX_WIDTH = 1440

    private const val JPEG_QUALITY = 92

    /**
     * 导出一本。
     *
     * @param pages 已经按当前显示顺序排好的页面（调用方负责排序与过滤）
     */
    suspend fun exportBook(
        context: Context,
        storage: ComicStorage,
        book: Book,
        pages: List<PageEntry>,
        format: Format,
        targetDir: DocumentFile,
        baseName: String,
        includeThumbs: Boolean = false,
        onProgress: (Progress) -> Unit = {}
    ): Result = runCatching {
        val dir = File(storage.dirOf(book.folder).absolutePath)
        val existing = pages.filter { File(dir, it.relPath).isFile }
        if (existing.isEmpty()) {
            return Result(false, message = "没有可导出的页面")
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
        val safeName = sanitize("${baseName}_$stamp")

        when (format) {
            Format.PDF_ORIGINAL -> exportOriginalPdf(context, storage, book, targetDir, safeName)
            Format.PDF_REBUILT -> exportRebuiltPdf(context, dir, existing, targetDir, safeName, onProgress)
            Format.ZIP -> exportZip(context, dir, storage, existing, targetDir, safeName, includeThumbs, onProgress)
            Format.LONG_IMAGE -> exportLongImages(context, dir, existing, targetDir, safeName, onProgress)
        }
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result(false, message = e.message ?: "导出失败")
    }

    // ------------------------------------------------------------ 原始 PDF

    private suspend fun exportOriginalPdf(
        context: Context,
        storage: ComicStorage,
        book: Book,
        targetDir: DocumentFile,
        baseName: String
    ): Result {
        val source = storage.sourcePdf(book.folder)
        if (!source.isFile || source.length() == 0L) {
            return Result(false, message = "这本书没有保留原始 PDF（图片/文件夹导入的书）")
        }
        coroutineContext.ensureActive()
        val doc = targetDir.createFile(Format.PDF_ORIGINAL.mime, "$baseName.pdf")
            ?: return Result(false, message = "无法在目标目录创建文件")
        val out = context.contentResolver.openOutputStream(doc.uri)
            ?: return Result(false, message = "无法写入目标文件")
        out.use { o -> source.inputStream().use { i -> i.copyTo(o, 64 * 1024) } }
        return Result(true, listOf(doc.uri), "已导出原始 PDF")
    }

    // ------------------------------------------------------------ 重排 PDF

    private suspend fun exportRebuiltPdf(
        context: Context,
        dir: File,
        pages: List<PageEntry>,
        targetDir: DocumentFile,
        baseName: String,
        onProgress: (Progress) -> Unit
    ): Result {
        val doc = targetDir.createFile(Format.PDF_REBUILT.mime, "$baseName.pdf")
            ?: return Result(false, message = "无法在目标目录创建文件")
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                onProgress(Progress("正在合成 PDF", index + 1, pages.size))
                val bmp = decodePage(File(dir, page.relPath)) ?: return@forEachIndexed
                try {
                    val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index + 1).create()
                    val p = pdf.startPage(info)
                    p.canvas.drawColor(Color.WHITE)
                    p.canvas.drawBitmap(bmp, 0f, 0f, null)
                    pdf.finishPage(p)
                } finally {
                    bmp.recycle()
                }
            }
            if (pdf.pages.size == 0) {
                return Result(false, message = "所有页面都无法解码")
            }
            context.contentResolver.openOutputStream(doc.uri)?.use { pdf.writeTo(it) }
                ?: return Result(false, message = "无法写入目标文件")
            return Result(true, listOf(doc.uri), "已导出 PDF（${pdf.pages.size} 页）")
        } finally {
            runCatching { pdf.close() }
        }
    }

    // ----------------------------------------------------------------- ZIP

    private suspend fun exportZip(
        context: Context,
        dir: File,
        storage: ComicStorage,
        pages: List<PageEntry>,
        targetDir: DocumentFile,
        baseName: String,
        includeThumbs: Boolean,
        onProgress: (Progress) -> Unit
    ): Result {
        val doc = targetDir.createFile(Format.ZIP.mime, "$baseName.zip")
            ?: return Result(false, message = "无法在目标目录创建文件")
        val raw = context.contentResolver.openOutputStream(doc.uri)
            ?: return Result(false, message = "无法写入目标文件")
        BufferedOutputStream(raw).use { buffered ->
            ZipOutputStream(buffered).use { zos ->
                pages.forEachIndexed { index, page ->
                    coroutineContext.ensureActive()
                    onProgress(Progress("正在打包", index + 1, pages.size))
                    val file = File(dir, page.relPath)
                    if (!file.isFile) return@forEachIndexed
                    val ext = file.extension.ifBlank { "jpg" }
                    val entryName = String.format(Locale.US, "%03d.%s", index + 1, ext)
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
                if (includeThumbs) {
                    pages.forEach { page ->
                        // 缩略图在 <书目录>/thumbs/<页名>.webp，按页名归类避免重名
                        val base = page.relPath.substringAfterLast('/').substringBeforeLast('.')
                        val thumb = File(dir, "thumbs/$base.webp")
                        if (!thumb.isFile) return@forEach
                        coroutineContext.ensureActive()
                        zos.putNextEntry(ZipEntry("thumbs/$base.webp"))
                        thumb.inputStream().use { it.copyTo(zos, 32 * 1024) }
                        zos.closeEntry()
                    }
                }
            }
        }
        return Result(true, listOf(doc.uri), "已导出压缩包（${pages.size} 页）")
    }

    // --------------------------------------------------------------- 长图

    /**
     * 长图导出。
     *
     * 分两步，第一步只读图片头算尺寸（不占内存），第二步逐页解码绘制、画完立即回收。
     * 高度累积到 [MAX_SLICE_HEIGHT] 就落盘另起一片，因此 1000 页也不会一次性加载。
     */
    private suspend fun exportLongImages(
        context: Context,
        dir: File,
        pages: List<PageEntry>,
        targetDir: DocumentFile,
        baseName: String,
        onProgress: (Progress) -> Unit
    ): Result {
        // 1) 只读尺寸
        val meta = ArrayList<Pair<PageEntry, Pair<Int, Int>>>(pages.size)
        pages.forEach { page ->
            coroutineContext.ensureActive()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(File(dir, page.relPath).absolutePath, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val scale = if (opts.outWidth > MAX_WIDTH) MAX_WIDTH.toFloat() / opts.outWidth else 1f
                val w = (opts.outWidth * scale).toInt()
                val h = (opts.outHeight * scale).toInt()
                meta += page to (w to h)
            }
        }
        if (meta.isEmpty()) return Result(false, message = "所有页面都无法读取尺寸")

        // 2) 规划分片
        val slices = ArrayList<MutableList<Pair<PageEntry, Pair<Int, Int>>>>()
        var current = ArrayList<Pair<PageEntry, Pair<Int, Int>>>()
        var currentHeight = 0
        meta.forEach { entry ->
            val h = entry.second.second.coerceAtMost(MAX_SLICE_HEIGHT)
            if (current.isNotEmpty() && currentHeight + h > MAX_SLICE_HEIGHT) {
                slices += current
                current = ArrayList()
                currentHeight = 0
            }
            current += entry
            currentHeight += h
        }
        if (current.isNotEmpty()) slices += current

        val uris = ArrayList<Uri>(slices.size)
        slices.forEachIndexed { sliceIndex, slice ->
            coroutineContext.ensureActive()
            val width = MAX_WIDTH
            val sliceHeight = slice.sumOf { it.second.second.coerceAtMost(MAX_SLICE_HEIGHT) }
            val canvasBitmap = Bitmap.createBitmap(width, sliceHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(canvasBitmap)
                canvas.drawColor(Color.WHITE)
                var y = 0f
                slice.forEachIndexed { i, (page, size) ->
                    coroutineContext.ensureActive()
                    onProgress(
                        Progress(
                            "正在拼接第 ${sliceIndex + 1}/${slices.size} 张",
                            sliceIndex * 10 + i + 1,
                            slices.size * 10
                        )
                    )
                    val bmp = decodePageScaled(File(dir, page.relPath), size.first)
                        ?: return@forEachIndexed
                    try {
                        val dst = Rect(0, y.toInt(), size.first, (y + size.second).toInt())
                        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    } finally {
                        bmp.recycle()
                    }
                    y += size.second
                }
                val suffix = if (slices.size > 1) "_${sliceIndex + 1}of${slices.size}" else ""
                val doc = targetDir.createFile(Format.LONG_IMAGE.mime, "$baseName$suffix.jpg")
                    ?: return Result(false, message = "无法在目标目录创建文件")
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    canvasBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                } ?: return Result(false, message = "无法写入目标文件")
                uris += doc.uri
            } finally {
                runCatching { canvasBitmap.recycle() }
            }
        }
        val tip = if (slices.size > 1) "已分为 ${slices.size} 张长图（单张过高）" else "已导出长图"
        return Result(true, uris, tip)
    }

    // ------------------------------------------------------------- 解码

    private fun decodePage(file: File): Bitmap? = runCatching {
        if (!file.isFile) return null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun decodePageScaled(file: File, targetWidth: Int): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    /** 文件名里去掉各平台不允许的字符，并压到合理长度 */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
        val trimmed = cleaned.ifBlank { "漫画" }.take(60)
        return trimmed
    }
}
