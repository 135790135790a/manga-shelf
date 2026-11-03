package com.mangashelf.reader.data

/**
 * 图片工作栏里一张图的「动图类型」。
 *
 * 列表页永远只显示首帧静态图（走 ThumbStore），只有预览页才按这个类型挑播放器，
 * 避免多个动图在列表里同时解码把内存打爆。
 */
enum class AnimationKind(val label: String, val animated: Boolean) {
    STATIC("静态图", false),
    GIF("GIF", true),
    WEBP_ANIM("WebP 动图", true),
    APNG("APNG", true),
    UGOIRA("Ugoira", true),
    LOTTIE("Lottie", true),
    SVGA("SVGA", true);

    companion object {
        private val byName = entries.associateBy { it.name }

        fun fromName(name: String?): AnimationKind =
            name?.let { byName[it] } ?: STATIC

        /** 预览页要用的播放器实现是否可用（Lottie/SVGA 依赖已打进包，恒 true） */
        fun isSupported(kind: AnimationKind): Boolean = kind != STATIC
    }
}
