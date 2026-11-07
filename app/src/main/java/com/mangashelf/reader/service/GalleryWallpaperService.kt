package com.mangashelf.reader.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
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
import com.mangashelf.reader.data.GalleryStorage
import com.mangashelf.reader.data.WallpaperTarget
import java.io.File

/**
 * 动图 / 静态图壁纸。
 *
 * 系统的 [android.app.WallpaperManager] 只能吃静态 Bitmap，动图必须自己开一个 WallpaperService
 * 逐帧画到 Surface 上。这里按 [AnimationKind] 分派：
 *
 * - STATIC / 其它格式的兜底：一张 Bitmap 居中裁切绘制
 * - GIF / APNG / 动图 WebP（API 28+）：[AnimatedImageDrawable] 自己按系统时间推进，这里只负责按帧 invalidate
 * - GIF（API 24–27）：[Movie] 手动 setTime 播放
 * - Ugoira：逐帧解码，只缓存当前帧，避免 500 帧全解进内存
 * - Lottie / SVGA：交给各自的 drawable 推进
 *
 * 任何一档解码失败都退化成首帧静态图，绝不让壁纸服务把进程拖崩。
 */
class GalleryWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GalleryEngine()

    private companion object {
        const val TAG = "GalleryWallpaper"
        const val FRAME_MS = 40L
    }

    private interface Renderer {
        fun draw(canvas: Canvas, width: Int, height: Int)
        fun release()
    }

    /** 居中裁切铺满（cover），与系统壁纸观感一致。 */
    private class BitmapRenderer(private val bitmap: Bitmap) : Renderer {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas, width: Int, height: Int) {
            canvas.drawColor(Color.BLACK)
            val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
        override fun release() { /* 由调用方负责回收 */ }
    }

    /** 光栅动图（GIF / APNG / 动图 WebP）走的 drawable 渲染器。 */
    @RequiresApi(Build.VERSION_CODES.P)
    private class DrawableRenderer(
        private val drawable: Drawable,
        private val contentW: Int,
        private val contentH: Int
    ) : Renderer {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas, width: Int, height: Int) {
            canvas.drawColor(Color.BLACK)
            val srcW = contentW.takeIf { it > 0 } ?: width
            val srcH = contentH.takeIf { it > 0 } ?: height
            val scale = maxOf(width.toFloat() / srcW, height.toFloat() / srcH)
            val dw = (srcW * scale).toInt()
            val dh = (srcH * scale).toInt()
            val left = (width - dw) / 2
            val top = (height - dh) / 2
            drawable.setBounds(left, top, left + dw, top + dh)
            drawable.draw(canvas)
        }
        override fun release() { (drawable as? AnimatedImageDrawable)?.stop() }
    }

    /** API 24–27 的 GIF 兜底：Movie 能精确按毫秒定位。 */
    private class MovieRenderer(private val movie: Movie) : Renderer {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val start = SystemClock.elapsedRealtime()
        override fun draw(canvas: Canvas, width: Int, height: Int) {
            canvas.drawColor(Color.BLACK)
            val duration = movie.duration().takeIf { it > 0 } ?: 1000
            val t = ((SystemClock.elapsedRealtime() - start) % duration).toInt()
            movie.setTime(t)
            val scale = maxOf(width.toFloat() / movie.width(), height.toFloat() / movie.height())
            canvas.save()
            canvas.translate((width - movie.width() * scale) / 2f, (height - movie.height() * scale) / 2f)
            canvas.scale(scale, scale)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            paint.alpha = 255
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

        override fun draw(canvas: Canvas, width: Int, height: Int) {
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
            val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            canvas.drawBitmap(bmp, null, dst, paint)
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
        override fun draw(canvas: Canvas, width: Int, height: Int) {
            canvas.drawColor(Color.BLACK)
            val dur = durationMs.coerceAtLeast(500)
            val progress = ((SystemClock.elapsedRealtime() - start) % dur).toFloat() / dur
            val srcW = contentW.takeIf { it > 0 } ?: width
            val srcH = contentH.takeIf { it > 0 } ?: height
            val scale = maxOf(width.toFloat() / srcW, height.toFloat() / srcH)
            val dw = (srcW * scale).toInt()
            val dh = (srcH * scale).toInt()
            val left = (width - dw) / 2
            val top = (height - dh) / 2
            drawable.setBounds(left, top, left + dw, top + dh)
            runCatching {
                drawable.javaClass.getMethod("setProgress", Float::class.javaPrimitiveType)
                    .invoke(drawable, progress)
            }
            drawable.draw(canvas)
        }
        override fun release() {}
    }

    private inner class GalleryEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var renderer: Renderer? = null
        private var visible = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val tick = object : Runnable {
            override fun run() {
                drawOnce()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(tick)
            if (visible) {
                drawOnce()
                handler.postDelayed(tick, FRAME_MS)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
            drawOnce()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            buildRenderer()
            drawOnce()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(tick)
            visible = false
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick)
            renderer?.release()
            renderer = null
            super.onDestroy()
        }

        private fun drawOnce() {
            val r = renderer ?: return
            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                r.draw(canvas, w, h)
            } catch (e: Throwable) {
                Log.w(TAG, "draw failed", e)
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun buildRenderer() {
            renderer?.release()
            renderer = null
            val entry = WallpaperTarget.current(applicationContext) ?: return
            val storage = GalleryStorage(applicationContext)
            val file = storage.fileOf(entry.relPath)
            if (!file.isFile) {
                Log.w(TAG, "wallpaper file missing: ${entry.relPath}")
                return
            }
            renderer = runCatching { create(entry.kind, storage, entry.relPath, file) }
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
            AnimationKind.STATIC -> BitmapFactory.decodeFile(file.absolutePath)?.let { BitmapRenderer(it) }

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

        private fun stillFallback(file: File): Renderer? =
            BitmapFactory.decodeFile(file.absolutePath)?.let { BitmapRenderer(it) }

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
                val w = (drawable as? AnimatedImageDrawable)?.intrinsicWidth ?: 0
                val h = (drawable as? AnimatedImageDrawable)?.intrinsicHeight ?: 0
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
}
