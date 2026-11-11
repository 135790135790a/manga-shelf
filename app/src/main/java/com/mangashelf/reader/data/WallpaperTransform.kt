package com.mangashelf.reader.data

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
 *
 * 序列化**不依赖 `org.json`**：Android 的 org.json 在 JVM 单元测试里是空壳（调用即抛
 * "Stub!"），而这个类的换算逻辑恰恰是最该被单测覆盖的部分。这里用手写读写，
 * 输出的 JSON 结构与旧版（`org.json.JSONObject`）完全一致，老数据仍能读。
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
        scale = if (scale.isNaN()) 1f else scale.coerceIn(MIN_SCALE, MAX_SCALE),
        tx = if (tx.isNaN()) 0f else tx.coerceIn(-1f, 1f),
        ty = if (ty.isNaN()) 0f else ty.coerceIn(-1f, 1f),
        rotation = ((rotation % 360) + 360) % 360
    )

    fun toJson(): String = buildString(64) {
        append("{\"s\":").append(scale)
        append(",\"tx\":").append(tx)
        append(",\"ty\":").append(ty)
        append(",\"fx\":").append(flipX)
        append(",\"fy\":").append(flipY)
        append(",\"r\":").append(rotation)
        append(",\"f\":\"").append(fill.name).append('"')
        append('}')
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 5f
        val DEFAULT = WallpaperTransform()

        fun fromJson(raw: String?): WallpaperTransform = runCatching {
            if (raw.isNullOrBlank()) return@runCatching DEFAULT
            WallpaperTransform(
                scale = numOf(raw, "s")?.toFloat() ?: 1f,
                tx = numOf(raw, "tx")?.toFloat() ?: 0f,
                ty = numOf(raw, "ty")?.toFloat() ?: 0f,
                flipX = boolOf(raw, "fx"),
                flipY = boolOf(raw, "fy"),
                rotation = numOf(raw, "r")?.toInt() ?: 0,
                fill = runCatching { FillMode.valueOf(strOf(raw, "f") ?: "COVER") }
                    .getOrDefault(FillMode.COVER)
            ).normalized()
        }.getOrDefault(DEFAULT)

        /** 取 `"key": <number>` 里的数值；找不到或解析失败返回 null。 */
        internal fun numOf(raw: String, key: String): Double? {
            val idx = raw.indexOf("\"$key\"")
            if (idx < 0) return null
            val colon = raw.indexOf(':', idx)
            if (colon < 0) return null
            val sb = StringBuilder()
            var i = colon + 1
            while (i < raw.length && i < colon + 40) {
                val c = raw[i]
                if (c.isDigit() || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                    sb.append(c)
                } else if (sb.isNotEmpty()) {
                    break
                } else if (c != ' ' && c != '\t') {
                    break
                }
                i++
            }
            return sb.toString().toDoubleOrNull()
        }

        internal fun boolOf(raw: String, key: String): Boolean {
            val idx = raw.indexOf("\"$key\"")
            if (idx < 0) return false
            val colon = raw.indexOf(':', idx)
            if (colon < 0) return false
            val tail = raw.substring(colon + 1, minOf(raw.length, colon + 12)).trimStart()
            return tail.startsWith("true")
        }

        internal fun strOf(raw: String, key: String): String? {
            val idx = raw.indexOf("\"$key\"")
            if (idx < 0) return null
            val q1 = raw.indexOf('"', raw.indexOf(':', idx))
            if (q1 < 0) return null
            val q2 = raw.indexOf('"', q1 + 1)
            if (q2 < 0) return null
            return raw.substring(q1 + 1, q2)
        }
    }
}
