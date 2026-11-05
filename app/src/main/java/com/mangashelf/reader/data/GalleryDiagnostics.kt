package com.mangashelf.reader.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片工作栏的自检报告（v1.6.3）。
 *
 * 起因：用户在真机上遇到「GIF / Ugoira 导入后只剩一张静态图」「相簿点进去空白」，
 * 但这台机器上没有 adb，拿不到 logcat。所以把判断依据做进 App：面板直接列出每张图的
 * 磁盘真实字节、文件头、嗅探结论、数据库里记的 kind / frames / duration、缩略图与帧目录状态。
 *
 * 输出是纯文本，用户复制出来回传即可定位问题——不需要日志、不需要设备连接。
 */
object GalleryDiagnostics {

    private const val MAGIC_BYTES = 16

    /**
     * 生成诊断文本。
     *
     * @param items 当前工作栏里的图片（通常是全部图片，按导入时间倒序取前若干张）
     */
    suspend fun collect(
        context: Context,
        items: List<ImageItem>,
        storage: GalleryStorage,
        limit: Int = 200
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("漫画书架 · 图片工作栏诊断")
        sb.appendLine("时间：${stamp()}")
        sb.appendLine("Android ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL}（${android.os.Build.MANUFACTURER}）")
        sb.appendLine("图片总数（数据库）：${items.size}，下表取前 ${minOf(limit, items.size)} 张")
        // 用 fileOf("") 拿根目录路径，避免依赖 GalleryStorage 的私有字段
        val rootDir = storage.fileOf("")
        sb.appendLine("根目录：${rootDir.absolutePath}")
        sb.appendLine("目录存在：${rootDir.isDirectory}，磁盘占用：${human(storage.totalSize())}")
        sb.appendLine()
        sb.appendLine("字段：名称 | relPath | 磁盘字节 | 文件头(前${MAGIC_BYTES}字节) | 重新嗅探 | 数据库kind | 帧数 | 时长ms | 尺寸 | 缩略图 | 帧目录")
        sb.appendLine()

        items.take(limit).forEachIndexed { index, item ->
            val file = storage.fileOf(item.relPath)
            val exists = file.isFile
            val bytes = if (exists) file.length() else -1L
            val head = if (exists) magic(file) else "-"
            val sniffed = if (exists) {
                runCatching { AnimationSniffer.sniff(file, item.name) }.getOrNull()
            } else {
                null
            }
            val sniffText = sniffed?.let { "${it.kind.name}/${it.mime}" } ?: "-"
            val thumb = storage.thumbFileOf(item.relPath).let { if (it.isFile) "${it.length()}B" else "无" }
            val frameDir = if (item.kind == AnimationKind.UGOIRA.name) {
                val dir = storage.ugoiraDir(item.relPath.substringBeforeLast('.'))
                if (dir.isDirectory) "${dir.listFiles()?.size ?: 0} 帧" else "目录缺失"
            } else {
                "-"
            }
            val size = if (item.width > 0 && item.height > 0) "${item.width}x${item.height}" else "-"
            sb.append(index + 1).append(". ")
                .append(item.name).append(" | ")
                .append(item.relPath).append(" | ")
                .append(if (bytes >= 0) human(bytes) else "文件缺失").append(" | ")
                .append(head).append(" | ")
                .append(sniffText).append(" | ")
                .append(item.kind).append(" | ")
                .append(item.frames).append(" | ")
                .append(item.durationMs).append(" | ")
                .append(size).append(" | ")
                .append(thumb).append(" | ")
                .append(frameDir)
                .appendLine()
        }

        if (items.isEmpty()) {
            sb.appendLine("（数据库里没有任何图片记录）")
        }
        sb.toString()
    }

    /** 读文件头，用十六进制串出来，肉眼就能分辨 GIF / PNG / RIFF / PK 头。 */
    private fun magic(file: File): String = runCatching {
        val buf = ByteArray(MAGIC_BYTES)
        val read = file.inputStream().use { it.read(buf) }
        if (read <= 0) return@runCatching "-"
        val hex = StringBuilder()
        for (i in 0 until read) {
            val v = buf[i].toInt() and 0xFF
            hex.append(HEX[v shr 4]).append(HEX[v and 0x0F])
            if (i != read - 1) hex.append(' ')
        }
        hex.toString()
    }.getOrDefault("-")

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun human(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.2f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
}
