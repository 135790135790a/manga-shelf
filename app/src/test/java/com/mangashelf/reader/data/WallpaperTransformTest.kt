package com.mangashelf.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 壁纸变换的纯 JVM 单测。
 *
 * 重点覆盖两件事：
 * 1. [WallpaperTransform.normalized] 的钳制规则（越界/NaN/负数旋转）
 * 2. JSON 往返 —— 这份配置存在 SharedPreferences 里，读错一次壁纸就会歪
 */
class WallpaperTransformTest {

    @Test
    fun `默认值往返不丢信息`() {
        val t = WallpaperTransform.DEFAULT
        assertEquals(t, WallpaperTransform.fromJson(t.toJson()))
    }

    @Test
    fun `完整字段往返`() {
        val t = WallpaperTransform(
            scale = 2.5f,
            tx = -0.4f,
            ty = 0.75f,
            flipX = true,
            flipY = false,
            rotation = 270,
            fill = FillMode.FIT
        )
        assertEquals(t, WallpaperTransform.fromJson(t.toJson()))
    }

    @Test
    fun `缩放越界被钳制到 0_25 到 5`() {
        assertEquals(5f, WallpaperTransform(scale = 100f).normalized().scale, 0.001f)
        assertEquals(0.25f, WallpaperTransform(scale = 0.01f).normalized().scale, 0.001f)
        assertEquals(0.25f, WallpaperTransform(scale = -3f).normalized().scale, 0.001f)
    }

    @Test
    fun `位移越界被钳制到 -1 到 1`() {
        assertEquals(1f, WallpaperTransform(tx = 9f, ty = -9f).normalized().tx, 0.001f)
        assertEquals(-1f, WallpaperTransform(tx = 9f, ty = -9f).normalized().ty, 0.001f)
    }

    @Test
    fun `NaN 不会让壁纸算成 NaN`() {
        val n = WallpaperTransform(scale = Float.NaN, tx = Float.NaN).normalized()
        assertEquals(1f, n.scale, 0.001f)
        assertEquals(0f, n.tx, 0.001f)
    }

    @Test
    fun `旋转归一化到 0 到 359`() {
        assertEquals(270, WallpaperTransform(rotation = -90).normalized().rotation)
        assertEquals(90, WallpaperTransform(rotation = 450).normalized().rotation)
        assertEquals(0, WallpaperTransform(rotation = 720).normalized().rotation)
        assertEquals(180, WallpaperTransform(rotation = 180).normalized().rotation)
    }

    @Test
    fun `空或 null 返回默认值`() {
        assertEquals(WallpaperTransform.DEFAULT, WallpaperTransform.fromJson(null))
        assertEquals(WallpaperTransform.DEFAULT, WallpaperTransform.fromJson(""))
        assertEquals(WallpaperTransform.DEFAULT, WallpaperTransform.fromJson("   "))
    }

    @Test
    fun `残缺 JSON 不崩且缺失字段用默认值`() {
        val half = """{"s":2.0,"tx":0.5}"""
        val t = WallpaperTransform.fromJson(half)
        assertEquals(2f, t.scale, 0.001f)
        assertEquals(0.5f, t.tx, 0.001f)
        assertEquals(0f, t.ty, 0.001f)
        assertEquals(0, t.rotation)
        assertEquals(FillMode.COVER, t.fill)
    }

    @Test
    fun `完全不是 JSON 时退回默认值`() {
        assertEquals(WallpaperTransform.DEFAULT, WallpaperTransform.fromJson("not a json at all"))
        assertEquals(WallpaperTransform.DEFAULT, WallpaperTransform.fromJson("{"))
    }

    @Test
    fun `未知填充模式退回 COVER`() {
        val t = WallpaperTransform.fromJson("""{"s":1.0,"f":"STRETCH"}""")
        assertEquals(FillMode.COVER, t.fill)
    }

    @Test
    fun `读取时同样执行钳制`() {
        val t = WallpaperTransform.fromJson("""{"s":99.0,"tx":-8.0,"r":-45}""")
        assertEquals(5f, t.scale, 0.001f)
        assertEquals(-1f, t.tx, 0.001f)
        assertEquals(315, t.rotation)
    }

    @Test
    fun `旧版 org_json 生成的带空格 JSON 仍能读`() {
        // 旧实现：JSONObject 输出不带空格，但历史数据或手工改过的可能带空格
        val legacy = """{ "s": 1.8, "tx": -0.25, "ty": 0.1, "fx": true, "fy": false, "r": 90, "f": "ORIGINAL" }"""
        val t = WallpaperTransform.fromJson(legacy)
        assertEquals(1.8f, t.scale, 0.001f)
        assertEquals(-0.25f, t.tx, 0.001f)
        assertEquals(true, t.flipX)
        assertEquals(90, t.rotation)
        assertEquals(FillMode.ORIGINAL, t.fill)
    }

    @Test
    fun `布尔字段解析`() {
        val t = WallpaperTransform.fromJson("""{"fx":true,"fy":false}""")
        assertEquals(true, t.flipX)
        assertEquals(false, t.flipY)
    }

    @Test
    fun `序列化后能被自身解析回同一对象`() {
        val cases = listOf(
            WallpaperTransform(),
            WallpaperTransform(scale = 0.25f, tx = -1f, ty = 1f, rotation = 90, fill = FillMode.ORIGINAL),
            WallpaperTransform(flipX = true, flipY = true, rotation = 180, fill = FillMode.FIT)
        )
        for (c in cases) {
            assertEquals(c, WallpaperTransform.fromJson(c.toJson()))
        }
    }
}
