package com.mangashelf.reader.data

import org.json.JSONObject

/** 图片小于画布时的填充策略。 */
enum class FillMode(val label: String) {
    COVER("铺满"),
    FIT("适应"),
    ORIGINAL("原尺寸")
}

/**
 * 壁纸的变换参数（编辑页产出，WallpaperService 每帧应用）。
 *
 * 现场统一用**归一化**量，不存像素：
 * - [scale] 相对「基础铺满比例」的倍数，1f 表示正好铺满画布
 * - [tx]/[ty] 相对画布的位移，取值 -1f..1f（1f = 整幅画面宽/高的一半）
 * - [rotation] 只允许 0/90/180/270
 *
 * 之所以不存像素：同一份配置要同时适配「预览画布」和「真实屏幕」，
 * 存像素的话换分辨率就全错位。
 */
data class WallpaperTransform(
    val scale: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val rotation: Int = 0,
    val fill: FillMode = FillMode.COVER
) {

    fun normalized(): WallpaperTransform = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        tx = tx.coerceIn(-1f, 1f),
        ty = ty.coerceIn(-1f, 1f),
        rotation = ((rotation % 360) + 360) % 360
    )

    fun toJson(): String = JSONObject().apply {
        put("s", scale.toDouble())
        put("tx", tx.toDouble())
        put("ty", ty.toDouble())
        put("fx", flipX)
        put("fy", flipY)
        put("r", rotation)
        put("f", fill.name)
    }.toString()

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 5f
        val DEFAULT = WallpaperTransform()

        fun fromJson(raw: String?): WallpaperTransform = runCatching {
            if (raw.isNullOrBlank()) return@runCatching DEFAULT
            val o = JSONObject(raw)
            WallpaperTransform(
                scale = o.optDouble("s", 1.0).toFloat(),
                tx = o.optDouble("tx", 0.0).toFloat(),
                ty = o.optDouble("ty", 0.0).toFloat(),
                flipX = o.optBoolean("fx", false),
                flipY = o.optBoolean("fy", false),
                rotation = o.optInt("r", 0),
                fill = runCatching { FillMode.valueOf(o.optString("f", "COVER")) }
                    .getOrDefault(FillMode.COVER)
            ).normalized()
        }.getOrDefault(DEFAULT)
    }
}
