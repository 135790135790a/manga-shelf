package com.mangashelf.reader.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import com.mangashelf.reader.data.AnimationKind
import com.mangashelf.reader.data.FillMode
import com.mangashelf.reader.data.GalleryStorage
import com.mangashelf.reader.data.WallpaperTarget
import com.mangashelf.reader.data.WallpaperTransform
import java.io.File

/**
 * 图片工作栏的壁纸服务。
 *
 * v1.6.5 起静态图也走这里（而不是 `WallpaperManager.setBitmap`），原因是：
 * **系统静态壁纸的视差由桌面启动器控制，应用没有 API 能开关它**；
 * 只有 WallpaperService 通过 [Engine.onOffsetsChanged] 才能做到「视差 / 静止」真正可控。
 *
 * 渲染管线（单次缩放，尽量不二次重采样）：
 * ```
 * 源图 --解码(原尺寸或按目标采样)--> 位图 --cover+变换矩阵--> Surface
 *                                     └─ 视差：横向平移安全区
 * ```
 */
class GalleryWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GalleryEngine()

    private inner class GalleryEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var renderer: Renderer? = null
        private var visible = false
        private var surfaceW = 0
        private var surfaceH = 0

        /** 当前壁纸配置；[WallpaperTarget.current] 每次重建渲染器时读取。 */
        private var entry: WallpaperTarget.Entry? = null
        private var transform: WallpaperTransform = WallpaperTransform.DEFAULT
        private var parallaxWanted = true

        /** 桌面滚动位置，0f..1f。视差关闭时忽略。 */
        @Volatile
        private var offsetX = 0.5f

        /** 是否收到过系统位移回调（部分 ROM 不回调，需要降级并提示）。 */
        private var offsetSeen = false
        private var parallaxActive = false

        private var loggedFirstFrame = false

        private val tick = object : Runnable {
            override fun run() {
                drawOnce()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            entry = WallpaperTarget.current(applicationContext)
            /**
             * 3 秒内没收到 onOffsetsChanged 就认为这台机器不提供位移回调：
             * 关闭视差，避免用户以为开了却没效果。
             */
            handler.postDelayed({
                if (visible && !offsetSeen && parallaxWanted) {
                    parallaxActive = false
                    Log.i(TAG, "no offset callback in 3s, parallax disabled (rom=${Build.MANUFACTURER})")
                    drawOnce()
                }
            }, 3000L)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(tick)
            if (visible) {
                if (parallaxWanted) parallaxActive = true
                drawOnce()
                handler.postDelayed(tick, FRAME_MS)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
            buildRenderer()
            drawOnce()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            buildRenderer()
            drawOnce()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacksAndMessages(null)
            visible = false
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            offsetSeen = true
            val v = xOffset.coerceIn(0f, 1f)
            if (kotlin.math.abs(v - offsetX) < 0.002f) return
            offsetX = v
            if (parallaxActive) drawOnce()
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            runCatching { renderer?.release() }
            renderer = null
            super.onDestroy()
        }

        private fun drawOnce() {
            val r = renderer ?: return
            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) return

            // 视差开启时画布比视口宽 2*range，给左右位移留安全区，保证不露黑边
            val range = if (parallaxActive) (w * PARALLAX_RATIO).toInt().coerceAtLeast(1) else 0
            val outW = w + 2 * range
            val outH = h
            // xOffset 0..1 映射到 0..2*range 的平移量；关闭视差时恒为 0
            val shift = if (range == 0) 0f else offsetX * (2 * range)

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas() ?: return
                canvas.save()
                canvas.translate(-(2 * range - shift), 0f)
                r.draw(canvas, outW, outH, transform)
                canvas.restore()
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true
                    Log.i(TAG, "first frame surface=${w}x$h out=${outW}x$outH range=$range parallax=$parallaxActive")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "draw failed", t)
            } finally {
                if (canvas != null) runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun buildRenderer() {
            runCatching { renderer?.release() }
            renderer = null
            loggedFirstFrame = false

            val e = entry ?: WallpaperTarget.current(applicationContext).also { entry = it } ?: return
            transform = e.transform
            parallaxWanted = e.parallax
            parallaxActive = e.parallax

            val storage = GalleryStorage(applicationContext)
            val file = storage.fileOf(e.relPath)
            if (!file.isFile) {
                Log.w(TAG, "wallpaper file missing: ${e.relPath}")
                return
            }
            renderer = runCatching { create(e.kind, storage, e.relPath, file) }
                .onFailure { Log.w(TAG, "create renderer failed, fallback to still", it) }
                .getOrNull()
                ?: runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                    .getOrNull()
                    ?.let { BitmapRenderer(it) }
        }

        private fun create(
            kind: AnimationKind,
            storage: GalleryStorage,
            relPath: String,
            file: File
        ): Renderer? = when (kind) {
            AnimationKind.STATIC -> decodeForScreen(file)?.let { BitmapRenderer(it) }

            AnimationKind.GIF,
            AnimationKind.WEBP_ANIM,
            AnimationKind.APNG -> animatedDrawable(file) ?: stillFallback(file)

            AnimationKind.UGOIRA -> {
                val uuid = relPath.substringBeforeLast('.')
                val dir = storage.ugoiraDir(uuid)
                val frames = runCatching {
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
                }.getOrDefault(emptyList())
                if (frames.isEmpty()) stillFallback(file) else UgoiraRenderer(frames, 80)
            }

            AnimationKind.LOTTIE -> lottieRenderer(file) ?: stillFallback(file)

            // SVGA 的动画由 SVGAImageView 驱动，WallpaperService 里没有 View，
            // 2.6.1 的 SVGADrawable 也没有可手动推进的接口 —— 只能出首帧。
            AnimationKind.SVGA -> stillFallback(file)
        }

        /** 静态图：按「屏幕宽 × 视差安全区」的目标尺寸解码，避免 4000px 原图白占内存。 */
        private fun decodeForScreen(file: File): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val targetW = (surfaceW * (1 + 2 * PARALLAX_RATIO)).toInt().coerceAtLeast(720)
            // 只降不升：源图比目标大才采样，小图保持原样（放大交给绘制阶段的 FILTER）
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()

        private fun stillFallback(file: File): Renderer? = decodeForScreen(file)?.let { BitmapRenderer(it) }

        /** GIF / APNG / 动图 WebP：API 28+ 统一走 ImageDecoder。 */
        private fun animatedDrawable(file: File): Renderer? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // 24–27 只有 GIF 有 Movie 可用，WebP/APNG 只能静态
                return if (isGif(file)) {
                    runCatching {
                        val movie = Movie.decodeFile(file.absolutePath)
                        movie?.let { MovieRenderer(it) }
                    }.getOrNull()
                } else {
                    stillFallback(file)
                }
            }
            return runCatching {
                val source = ImageDecoder.createSource(file)
                val drawable = ImageDecoder.decodeDrawable(source)
                (drawable as? AnimatedImageDrawable)?.start()
                val w = (drawable as? AnimatedImageDrawable)?.intrinsicWidth ?: drawable.intrinsicWidth
                val h = (drawable as? AnimatedImageDrawable)?.intrinsicHeight ?: drawable.intrinsicHeight
                DrawableRenderer(drawable, w, h)
            }.getOrNull()
        }

        private fun isGif(file: File): Boolean = runCatching {
            file.inputStream().use { ins ->
                val head = ByteArray(3)
                ins.read(head) == 3 && String(head, Charsets.US_ASCII) == "GIF"
            }
        }.getOrDefault(false)

        private fun lottieRenderer(file: File): Renderer? = runCatching {
            val json = file.readText()
            val composition = com.airbnb.lottie.LottieCompositionFactory
                .fromJsonStringSync(json, file.absolutePath)
                .value ?: return null
            val drawable = com.airbnb.lottie.LottieDrawable()
            drawable.composition = composition
            drawable.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
            LottieRenderer(
                drawable,
                composition.duration.toInt(),
                composition.bounds.width(),
                composition.bounds.height()
            )
        }.getOrNull()
    }

    companion object {
        private const val TAG = "WPaper"
        private const val FRAME_MS = 40L

        /** 视差安全区：左右各留屏幕宽的 12%，覆盖主流启动器的位移幅度。 */
        const val PARALLAX_RATIO = 0.12f
    }
}

/** 在 `outW × outH` 的虚拟画布坐标里绘制。视差平移由调用方处理。 */
private interface Renderer {
    fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform)
    fun release()
}

/** 把「源尺寸 + 变换」换算成画布上的一个居中矩形，供各渲染器与省电模式复用。 */
internal fun placeFrame(
    canvas: Canvas,
    srcW: Float,
    srcH: Float,
    outW: Int,
    outH: Int,
    t: WallpaperTransform
): Boolean {
    if (srcW <= 0f || srcH <= 0f || outW <= 0 || outH <= 0) return false
    val rotated = t.rotation % 180 != 0
    val effW = if (rotated) srcH else srcW
    val effH = if (rotated) srcW else srcH
    val base = when (t.fill) {
        FillMode.COVER -> maxOf(outW / effW, outH / effH)
        FillMode.FIT -> minOf(outW / effW, outH / effH)
        FillMode.ORIGINAL -> 1f
    }
    val s = (base * t.scale).coerceAtLeast(0.01f)
    canvas.translate(outW / 2f + t.tx * outW / 2f, outH / 2f + t.ty * outH / 2f)
    if (t.rotation != 0) canvas.rotate(t.rotation.toFloat())
    canvas.scale(s * (if (t.flipX) -1f else 1f), s * (if (t.flipY) -1f else 1f))
    return true
}

private class BitmapRenderer(private val bitmap: Bitmap) : Renderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform) {
        canvas.drawColor(Color.BLACK)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        canvas.save()
        if (placeFrame(canvas, w, h, outW, outH, t)) {
            canvas.drawBitmap(bitmap, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), paint)
        }
        canvas.restore()
    }
    override fun release() { /* 由调用方负责回收 */ }
}

@RequiresApi(Build.VERSION_CODES.P)
private class DrawableRenderer(
    private val drawable: Drawable,
    private val contentW: Int,
    private val contentH: Int
) : Renderer {
    override fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform) {
        canvas.drawColor(Color.BLACK)
        val w = contentW.toFloat().takeIf { it > 0f } ?: outW.toFloat()
        val h = contentH.toFloat().takeIf { it > 0f } ?: outH.toFloat()
        canvas.save()
        if (placeFrame(canvas, w, h, outW, outH, t)) {
            drawable.setBounds((-w / 2f).toInt(), (-h / 2f).toInt(), (w / 2f).toInt(), (h / 2f).toInt())
            drawable.draw(canvas)
        }
        canvas.restore()
    }
    override fun release() { (drawable as? AnimatedImageDrawable)?.stop() }
}

/** API 24–27 的 GIF 兜底：Movie 能精确按毫秒定位。 */
private class MovieRenderer(private val movie: Movie) : Renderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val start = SystemClock.elapsedRealtime()
    override fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform) {
        canvas.drawColor(Color.BLACK)
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val elapsed = ((SystemClock.elapsedRealtime() - start) % duration).toInt()
        movie.setTime(elapsed)
        val w = movie.width().toFloat()
        val h = movie.height().toFloat()
        canvas.save()
        if (placeFrame(canvas, w, h, outW, outH, t)) {
            movie.draw(canvas, -w / 2f, -h / 2f, paint)
        }
        canvas.restore()
    }
    override fun release() {}
}

/**
 * Ugoira：帧是独立文件，全解会 OOM。
 * 只保留「当前帧」与「下一帧」两张 Bitmap，切换时 recycle 旧帧。
 */
private class UgoiraRenderer(
    private val frames: List<File>,
    private val frameMs: Int
) : Renderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var currentIndex = -1
    private var current: Bitmap? = null
    private var nextIndex = -1
    private var next: Bitmap? = null
    private val start = SystemClock.elapsedRealtime()

    private fun decode(frame: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(frame.absolutePath)
    }.getOrNull()

    override fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform) {
        if (frames.isEmpty()) return
        canvas.drawColor(Color.BLACK)
        val step = frameMs.coerceAtLeast(30).toLong()
        val index = (((SystemClock.elapsedRealtime() - start) / step) % frames.size).toInt()
        if (index != currentIndex) {
            if (nextIndex == index && next != null) {
                current?.recycle()
                current = next
                next = null
                nextIndex = -1
            } else {
                current?.recycle()
                current = decode(frames[index])
            }
            currentIndex = index
            // 预取下一帧，避免每帧都同步解码造成卡顿
            val ni = (index + 1) % frames.size
            if (nextIndex != ni) {
                next?.recycle()
                next = decode(frames[ni])
                nextIndex = ni
            }
        }
        val bmp = current ?: return
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        canvas.save()
        if (placeFrame(canvas, w, h, outW, outH, t)) {
            canvas.drawBitmap(bmp, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), paint)
        }
        canvas.restore()
    }

    override fun release() {
        current?.recycle()
        current = null
        next?.recycle()
        next = null
    }
}

/** Lottie：按时间轴 setProgress 驱动。 */
private class LottieRenderer(
    private val drawable: Drawable,
    private val durationMs: Int,
    private val contentW: Int,
    private val contentH: Int
) : Renderer {
    private val start = SystemClock.elapsedRealtime()
    override fun draw(canvas: Canvas, outW: Int, outH: Int, t: WallpaperTransform) {
        canvas.drawColor(Color.BLACK)
        val dur = durationMs.coerceAtLeast(500)
        val progress = ((SystemClock.elapsedRealtime() - start) % dur).toFloat() / dur
        val w = contentW.toFloat().takeIf { it > 0f } ?: outW.toFloat()
        val h = contentH.toFloat().takeIf { it > 0f } ?: outH.toFloat()
        canvas.save()
        if (placeFrame(canvas, w, h, outW, outH, t)) {
            drawable.setBounds((-w / 2f).toInt(), (-h / 2f).toInt(), (w / 2f).toInt(), (h / 2f).toInt())
            runCatching {
                drawable.javaClass.getMethod("setProgress", Float::class.javaPrimitiveType)
                    .invoke(drawable, progress)
            }
            drawable.draw(canvas)
        }
        canvas.restore()
    }
    override fun release() {}
}
