package com.mangashelf.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Ugoira 的一帧：解包后的图片 + 该帧停留毫秒。 */
data class UgoiraFrame(val file: File, val delayMs: Int)

/**
 * Ugoira 解包器。
 *
 * Ugoira 的原始形态是「zip 里一堆 jpg + 一个 json 记每帧延时」。导入时把它解开到
 * `files/gallery/ugoira/<uuid>/`，播放时按 [UgoiraFrame.delayMs] 逐帧切换。
 *
 * 为什么导入时就解开而不是播放时再解：
 * zip 是随机访问的，边播边解会在每帧都开一次 ZipFile，手机上会明显掉帧；
 * 解开后播放只是读本地小图，跟普通图库图片一样。
 */
object UgoiraParser {

    private val FILE_RE = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
    private val DELAY_RE = Regex("\"delay\"\\s*:\\s*(\\d+)")
    private val DEFAULT_DELAY = 100

    /**
     * 解包到 [destDir]。返回帧列表；空列表表示这个 zip 不是合法的 Ugoira。
     * 调用方负责在失败时删除 [destDir]。
     */
    suspend fun unpack(zipFile: File, destDir: File): List<UgoiraFrame> = withContext(Dispatchers.IO) {
        runCatching { unpackBlocking(zipFile, destDir) }.getOrDefault(emptyList())
    }

    private fun unpackBlocking(zipFile: File, destDir: File): List<UgoiraFrame> {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            val imageEntries = entries.filter { !it.isDirectory && it.name.isFrameImage() }
            if (imageEntries.isEmpty()) return emptyList()

            val jsonEntry = entries.firstOrNull {
                !it.isDirectory && it.name.endsWith(".json") && !it.name.startsWith("__MACOSX/")
            }

            // json 里的顺序就是播放顺序；拿不到 json 就按文件名自然排序兜底
            val order = ArrayList<Pair<String, Int>>()
            if (jsonEntry != null) {
                val text = runCatching {
                    zip.getInputStream(jsonEntry).bufferedReader().use { it.readText() }
                }.getOrDefault("")
                val files = FILE_RE.findAll(text).map { it.groupValues[1] }.toList()
                val delays = DELAY_RE.findAll(text).map { it.groupValues[1].toInt() }.toList()
                files.forEachIndexed { i, name ->
                    order += name to (delays.getOrNull(i) ?: DEFAULT_DELAY)
                }
            }
            if (order.isEmpty()) {
                imageEntries.sortedBy { it.name.lowercase() }.forEach { order += it.name to DEFAULT_DELAY }
            }

            if (!destDir.exists() && !destDir.mkdirs()) return emptyList()

            val byName = imageEntries.associateBy { it.name }
            val frames = ArrayList<UgoiraFrame>(order.size)
            var seq = 0
            for ((name, delay) in order) {
                val entry = byName[name] ?: byName.entries.firstOrNull { it.key.endsWith(name) }?.value ?: continue
                val ext = entry.name.substringAfterLast('.', "jpg").lowercase()
                val out = File(destDir, String.format("%05d.%s", seq, ext))
                runCatching {
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                }.onSuccess {
                    frames += UgoiraFrame(out, delay.coerceIn(10, 10_000))
                    seq++
                }
            }
            // 逐帧延时写盘：v1.6.2 起播放按真实延时走，不再用「总时长 ÷ 帧数」平均
            if (frames.isNotEmpty()) saveManifest(destDir, frames)
            return frames
        }
    }

    private const val MANIFEST = "frames.json"

    /** 把帧名与延时写成 `frames.json`，播放与统计都读它。 */
    fun saveManifest(destDir: File, frames: List<UgoiraFrame>): Boolean = runCatching {
        val arr = org.json.JSONArray()
        frames.forEach { f ->
            arr.put(
                org.json.JSONObject()
                    .put("file", f.file.name)
                    .put("delay", f.delayMs)
            )
        }
        File(destDir, MANIFEST).writeText(arr.toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /**
     * 读回 `frames.json`。返回 null 表示没有清单（老数据），
     * 调用方退化为「按文件名排序 + 默认延时」。
     */
    fun loadManifest(destDir: File): List<UgoiraFrame>? = runCatching {
        val f = File(destDir, MANIFEST)
        if (!f.isFile) return null
        val arr = org.json.JSONArray(f.readText(Charsets.UTF_8))
        val out = ArrayList<UgoiraFrame>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("file")
            if (name.isEmpty()) continue
            val file = File(destDir, name)
            if (!file.isFile) continue
            out += UgoiraFrame(file, o.optInt("delay", DEFAULT_DELAY).coerceIn(10, 10_000))
        }
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun String.isFrameImage(): Boolean {
        if (startsWith("__MACOSX/") || contains("/.")) return false
        val ext = substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp")
    }

    /** 总时长（毫秒），用于元数据与 UI 显示 */
    fun totalDuration(frames: List<UgoiraFrame>): Int =
        frames.sumOf { it.delayMs }.coerceIn(0, 60 * 60 * 1000)
}
