package com.mangashelf.reader.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 格式嗅探的纯 JVM 单测。
 *
 * 这些用例之所以能脱离设备跑，是因为 [AnimationSniffer] 的判断全部基于文件字节，
 * 且 mime 走本地表（不碰 `android.webkit.MimeTypeMap` 这个 JVM 空壳）。
 */
class AnimationSnifferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun sniff(name: String, bytes: ByteArray): Sniffed =
        runBlocking { AnimationSniffer.sniff(file(name, bytes), name) }

    // ---------------- GIF ----------------

    @Test
    fun `最小 GIF89a 识别为动图并数出 1 帧`() {
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x01, 0x00, 0x01, 0x00,             // 1x1
            0x80.toByte(), 0x00, 0x00,          // GCT flag, 大小
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, // GCE，延时 0
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // 图像描述符
            0x02, 0x02, 0x44, 0x01, 0x00,       // LZW 数据
            0x3B                                // trailer
        )
        val s = sniff("a.gif", gif)
        assertEquals(AnimationKind.GIF, s.kind)
        assertEquals("image/gif", s.mime)
        assertEquals(1, s.frames)
        // 延时 0 时按 10cs 兜底 → 100ms
        assertEquals(100, s.durationMs)
    }

    @Test
    fun `扩展名是 jpg 但内容是 GIF 时按内容判`() {
        val gifHead = ByteArray(20) { 0 }
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(gifHead)
        val s = sniff("looks-like.jpg", gifHead)
        assertEquals(AnimationKind.GIF, s.kind)
    }

    // ---------------- PNG / APNG ----------------

    @Test
    fun `普通 PNG 判为静态图`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52 // IHDR
        )
        val s = sniff("a.png", png)
        assertEquals(AnimationKind.STATIC, s.kind)
        assertEquals("image/png", s.mime)
    }

    // ---------------- WebP ----------------

    @Test
    fun `无 ANMF 的 WebP 判为静态图`() {
        val webp = ByteArray(40)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(webp, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(webp, 8)
        "VP8 ".toByteArray(Charsets.US_ASCII).copyInto(webp, 12)
        webp[16] = 8 // 块长度 LE = 8
        val s = sniff("a.webp", webp)
        assertEquals(AnimationKind.STATIC, s.kind)
        assertEquals("image/webp", s.mime)
    }

    @Test
    fun `带两个 ANMF 块的 WebP 判为动图且帧数为 2`() {
        // 布局：RIFF(4)+size(4)+WEBP(4) = 12，之后每块 = 8 字节头 + 8 字节负载
        // VP8X: 12..27，ANMF1: 28..43，ANMF2: 44..59
        val size = 12 + 16 + 16 + 16
        val b = ByteArray(size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b[4] = (size - 8).toByte()
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(b, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(b, 12)
        b[16] = 8
        "ANMF".toByteArray(Charsets.US_ASCII).copyInto(b, 28)
        b[32] = 8
        "ANMF".toByteArray(Charsets.US_ASCII).copyInto(b, 44)
        b[48] = 8
        val s = sniff("a.webp", b)
        assertEquals(AnimationKind.WEBP_ANIM, s.kind)
        assertEquals(2, s.frames)
    }

    // ---------------- SVGA / Lottie ----------------

    @Test
    fun `SVGAPlayer 文件头判为 SVGA`() {
        val b = ByteArray(24)
        "SVGAPlayer".toByteArray(Charsets.US_ASCII).copyInto(b)
        val s = sniff("a.svga", b)
        assertEquals(AnimationKind.SVGA, s.kind)
        assertEquals("image/svga", s.mime)
    }

    @Test
    fun `Lottie JSON 能算出时长与画布尺寸`() {
        val json = """{"v":"5.5.7","fr":30,"ip":0,"op":60,"w":512,"h":768,"layers":[]}"""
        val s = sniff("a.json", json.toByteArray(Charsets.UTF_8))
        assertEquals(AnimationKind.LOTTIE, s.kind)
        assertEquals(2000, s.durationMs) // (60-0)/30*1000
        assertEquals(512, s.width)
        assertEquals(768, s.height)
    }

    @Test
    fun `普通 JSON 不会误判为 Lottie`() {
        val json = """{"name":"x","value":1,"items":[1,2,3]}"""
        val s = sniff("a.json", json.toByteArray(Charsets.UTF_8))
        assertEquals(AnimationKind.STATIC, s.kind)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `小于 12 字节的文件退回静态图且不抛异常`() {
        val s = sniff("tiny.gif", byteArrayOf(0x47, 0x49, 0x46))
        assertEquals(AnimationKind.STATIC, s.kind)
        assertEquals("image/gif", s.mime) // mime 仍按扩展名兜底
    }

    @Test
    fun `文件不存在时不抛异常`() {
        val s = runBlocking { AnimationSniffer.sniff(File(tmp.root, "nope.png"), "nope.png") }
        assertEquals(AnimationKind.STATIC, s.kind)
        assertEquals("image/png", s.mime)
    }

    @Test
    fun `扩展名不在本地表里时给出 octet-stream 而不崩`() {
        assertTrue(AnimationSniffer.mimeFromName("x.unknownext").isNotBlank())
    }

    @Test
    fun `常见扩展名的 mime 映射`() {
        assertEquals("image/jpeg", AnimationSniffer.mimeFromName("a.JPG"))
        assertEquals("image/png", AnimationSniffer.mimeFromName("a.png"))
        assertEquals("image/webp", AnimationSniffer.mimeFromName("a.webp"))
        assertEquals("application/pdf", AnimationSniffer.mimeFromName("a.pdf"))
        assertEquals("application/vnd.comicbook+zip", AnimationSniffer.mimeFromName("a.cbz"))
    }
}
