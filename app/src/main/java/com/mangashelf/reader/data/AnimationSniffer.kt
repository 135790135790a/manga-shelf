package com.mangashelf.reader.data

import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** 嗅探结果：类型 + mime + 元数据。任何一步失败都退回 STATIC，不让导入因为嗅探崩掉。 */
data class Sniffed(
    val kind: AnimationKind,
    val mime: String,
    val frames: Int = 0,
    val durationMs: Int = 0,
    /** 非位图格式（Lottie/SVGA）从文件里读到的画布尺寸；拿不到为 0 */
    val width: Int = 0,
    val height: Int = 0
)

/**
 * 按文件头嗅探真实格式。
 *
 * 一律以**内容**为准，扩展名只用来兜底显示 mime：
 * 用户把 .gif 改名成 .jpg 的情况很常见，按扩展名判断会得到一张「静态图」然后播不动。
 * 反过来，扩展名是 .gif 但内容不是 GIF 的，按内容判成 STATIC 也不会崩。
 */
object AnimationSniffer {

    private const val MAX_HEAD = 32

    suspend fun sniff(file: File, displayName: String): Sniffed = withContext(Dispatchers.IO) {
        runCatching { sniffBlocking(file, displayName) }
            .getOrElse { Sniffed(AnimationKind.STATIC, mimeFromName(displayName)) }
    }

    private fun sniffBlocking(file: File, displayName: String): Sniffed {
        if (!file.isFile || file.length() < 12) {
            return Sniffed(AnimationKind.STATIC, mimeFromName(displayName))
        }
        val head = ByteArray(MAX_HEAD)
        val read = RandomAccessFile(file, "r").use { raf ->
            raf.read(head)
        }
        if (read < 12) return Sniffed(AnimationKind.STATIC, mimeFromName(displayName))

        // 1) GIF：GIF87a / GIF89a
        if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) {
            val gif = scanGif(file)
            return Sniffed(AnimationKind.GIF, "image/gif", gif.first, gif.second)
        }

        // 2) PNG / APNG：89 50 4E 47，扫 acTL 块
        if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        ) {
            val frames = apngFrameCount(file)
            return if (frames > 0) {
                Sniffed(AnimationKind.APNG, "image/apng", frames, 0)
            } else {
                Sniffed(AnimationKind.STATIC, "image/png")
            }
        }

        // 3) WebP：RIFF....WEBP，扫 ANIM 块
        if (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
        ) {
            val anim = webpAnimFrames(file)
            return if (anim > 0) {
                Sniffed(AnimationKind.WEBP_ANIM, "image/webp", anim, 0)
            } else {
                Sniffed(AnimationKind.STATIC, "image/webp")
            }
        }

        // 4) SVGA：自定义二进制，头部是 "SVGAPlayer"
        if (startsWith(head, "SVGAPlayer")) {
            return Sniffed(AnimationKind.SVGA, "image/svga")
        }

        // 5) ZIP 家族：Ugoira（帧图 + json）或 .lottie（animation.json + images/）
        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            return sniffZip(file, displayName)
        }

        // 6) JSON：Lottie
        val textHead = String(head, Charsets.UTF_8).trimStart()
        if (textHead.startsWith("{")) {
            val whole = runCatching { file.readText() }.getOrNull()
            if (whole != null && looksLottie(whole)) {
                return Sniffed(
                    kind = AnimationKind.LOTTIE,
                    mime = "application/json",
                    durationMs = lottieDurationMs(whole),
                    width = numField(whole, "w")?.toInt() ?: 0,
                    height = numField(whole, "h")?.toInt() ?: 0
                )
            }
        }

        return Sniffed(AnimationKind.STATIC, mimeFromName(displayName))
    }

    // ---------------- GIF ----------------

    /** 返回 (帧数, 总时长毫秒)。只扫描、不解码，1 万帧的 GIF 也就几十毫秒。 */
    private fun scanGif(file: File): Pair<Int, Int> {
        var frames = 0
        var durationMs = 0
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(13) // "GIF89a" + 逻辑屏幕宽高 4 + packed 1 + 背景色 1 + 像素比 1
                val packed = raf.read() and 0xFF
                if (packed and 0x80 != 0) {
                    raf.skipBytes(3 * (1 shl ((packed and 0x07) + 1)))
                }
                var delayCs = 0
                val buf = ByteArray(4)
                while (true) {
                    val marker = raf.read()
                    if (marker < 0) break
                    when (marker) {
                        0x3B -> break // trailer
                        0x21 -> { // 扩展
                            val label = raf.read()
                            if (label == 0xF9) {
                                raf.read(buf, 0, 4) // blockSize + packed + delay(2)
                                delayCs = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                                raf.read() // 终止符 0x00
                            } else {
                                skipSubBlocks(raf)
                            }
                        }
                        0x2C -> { // 图像描述符
                            frames++
                            durationMs += (if (delayCs <= 0) 10 else delayCs) * 10
                            raf.skipBytes(8) // left, top, width, height
                            val ip = raf.read() and 0xFF
                            if (ip and 0x80 != 0) {
                                raf.skipBytes(3 * (1 shl ((ip and 0x07) + 1)))
                            }
                            raf.read() // LZW 最小码长
                            skipSubBlocks(raf)
                        }
                        else -> break
                    }
                }
            }
        }
        return frames to durationMs
    }

    private fun skipSubBlocks(raf: RandomAccessFile) {
        while (true) {
            val size = raf.read()
            if (size <= 0) break
            raf.skipBytes(size)
        }
    }

    // ---------------- APNG ----------------

    private fun apngFrameCount(file: File): Int {
        var frames = 0
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.skipBytes(8) // PNG 签名
                val lenBuf = ByteArray(4)
                val type = ByteArray(4)
                while (true) {
                    if (raf.read(lenBuf) < 4) break
                    val len = beInt(lenBuf)
                    if (len < 0 || raf.read(type) < 4) break
                    val name = String(type, Charsets.US_ASCII)
                    if (name == "acTL") {
                        val data = ByteArray(8)
                        raf.readFully(data)
                        frames = beInt(data)
                        break
                    }
                    raf.skipBytes(len + 4L.toInt()) // data + CRC
                }
            }
        }
        return frames
    }

    // ---------------- WebP ----------------

    private fun webpAnimFrames(file: File): Int {
        var frames = 0
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(12) // RIFF + size + WEBP
                val hdr = ByteArray(8)
                while (true) {
                    if (raf.read(hdr) < 8) break
                    val name = String(hdr, 0, 4, Charsets.US_ASCII)
                    val size = leInt(hdr, 4)
                    if (name == "ANIM") {
                        raf.skipBytes(4) // 背景色
                        val b = raf.read()
                        frames = if (b < 0) 0 else b
                        break
                    }
                    if (name == "VP8X") {
                        // VP8X 里没有帧数，继续往后找 ANIM
                        raf.skipBytes(size + (size and 1))
                    } else {
                        raf.skipBytes(size + (size and 1))
                    }
                }
            }
        }
        return frames
    }

    // ---------------- ZIP ----------------

    private fun sniffZip(file: File, displayName: String): Sniffed {
        val names = runCatching {
            ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        }.getOrNull() ?: return Sniffed(AnimationKind.STATIC, mimeFromName(displayName))

        if (names.any { it.endsWith("animation.json") || it.endsWith(".lottie") }) {
            val dur = runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.entries().toList().firstOrNull { it.name.endsWith("animation.json") }
                    if (entry == null) 0 else lottieDurationMs(zip.getInputStream(entry).reader().readText())
                }
            }.getOrDefault(0)
            return Sniffed(AnimationKind.LOTTIE, "application/zip", 0, dur)
        }

        val imageEntries = names.filter { it.isImageEntry() }
        val hasJson = names.any { it.endsWith(".json") }
        if (hasJson || imageEntries.size >= 2) {
            return Sniffed(AnimationKind.UGOIRA, "application/zip", imageEntries.size, 0)
        }
        return Sniffed(AnimationKind.STATIC, mimeFromName(displayName))
    }

    private fun String.isImageEntry(): Boolean {
        if (endsWith('/')) return false
        val ext = substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }

    // ---------------- Lottie ----------------

    private fun looksLottie(text: String): Boolean {
        val t = text.take(4096)
        return t.contains("\"layers\"") || (t.contains("\"v\"") && t.contains("\"fr\"")) ||
            (t.contains("\"ip\"") && t.contains("\"op\""))
    }

    /** Lottie 总时长 = (op - ip) / fr 秒 */
    private fun lottieDurationMs(text: String): Int {
        val ip = numField(text, "ip") ?: 0.0
        val op = numField(text, "op") ?: return 0
        val fr = numField(text, "fr") ?: 30.0
        if (fr <= 0.0) return 0
        return (((op - ip) / fr) * 1000).toInt().coerceIn(0, 60 * 60 * 1000)
    }

    private fun numField(text: String, key: String): Double? {
        val idx = text.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = text.indexOf(':', idx)
        if (colon < 0) return null
        val sb = StringBuilder()
        for (i in colon + 1 until minOf(text.length, colon + 40)) {
            val c = text[i]
            if (c.isDigit() || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') sb.append(c)
        }
        return sb.toString().toDoubleOrNull()
    }

    // ---------------- 工具 ----------------

    private fun startsWith(head: ByteArray, ascii: String): Boolean {
        if (head.size < ascii.length) return false
        for (i in ascii.indices) {
            if (head[i] != ascii[i].code.toByte()) return false
        }
        return true
    }

    private fun beInt(b: ByteArray, off: Int = 0): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
