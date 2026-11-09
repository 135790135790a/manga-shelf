package com.mangashelf.reader.data

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import com.mangashelf.reader.service.GalleryWallpaperService
import com.mangashelf.reader.service.placeFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把图片工作栏里的一张图设为壁纸。
 *
 * v1.6.5 起**默认全部走 [GalleryWallpaperService]**（静态图也一样），
 * 因为只有动态壁纸能在 [android.service.wallpaper.WallpaperService.Engine.onOffsetsChanged]
 * 里控制「视差 / 静止」；系统静态壁纸的视差由桌面启动器决定，应用管不了。
 *
 * 保留一条「省电模式」旁路：静态图仍可用 `WallpaperManager.setBitmap` 直接铺，
 * 代价是无视差、且只能设成一张图（动图不支持）。
 */
object WallpaperSetter {

    data class Result(val ok: Boolean, val message: String)

    /**
     * 统一入口。
     *
     * @param powerSave true 时静态图走系统静态壁纸（省电），动图仍走服务（否则不会动）
     */
    suspend fun apply(
        context: Context,
        relPath: String,
        kind: AnimationKind,
        transform: WallpaperTransform,
        parallax: Boolean,
        powerSave: Boolean
    ): Result = withContext(Dispatchers.IO) {
        val storage = GalleryStorage(context)
        val file = storage.fileOf(relPath)
        if (!file.isFile) return@withContext Result(false, "这张图的文件找不到了，请重新导入")
        if (powerSave && kind == AnimationKind.STATIC) {
            applyStaticBaked(context, file, transform)
        } else {
            applyLive(context, relPath, kind, transform, parallax)
        }
    }

    /**
     * 省电模式：把裁剪/翻转/旋转烘焙成一张正好等于屏幕的位图，交给系统静态壁纸。
     * 视差交给启动器（我们无法控制，UI 上有说明）。
     */
    private fun applyStaticBaked(context: Context, file: File, t: WallpaperTransform): Result = runCatching {
        val m = context.resources.displayMetrics
        val w = maxOf(m.widthPixels, 720)
        val h = maxOf(m.heightPixels, 720)
        val bmp = renderStatic(file, t, w, h)
            ?: return@runCatching Result(false, "这张图片解码失败了")
        WallpaperManager.getInstance(context).setBitmap(
            bmp,
            null,
            true,
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        )
        bmp.recycle()
        Result(true, "已设为系统静态壁纸（省电模式：无视差，视差由桌面决定）")
    }.getOrElse { e ->
        Result(false, "设置壁纸失败：${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 动图与「非省电」的静态图：写目标 + 唤起系统动态壁纸确认框。
     * 部分国产 ROM 没有这个 Activity 时给明确降级提示，而不是静默失败。
     */
    private fun applyLive(
        context: Context,
        relPath: String,
        kind: AnimationKind,
        transform: WallpaperTransform,
        parallax: Boolean
    ): Result {
        // SVGA 的动画只能由 SVGAImageView 驱动，壁纸服务里没有 View，2.6.1 也没有手动推进接口。
        if (kind == AnimationKind.SVGA) {
            return Result(
                false,
                "SVGA 动画暂不支持设为壁纸（引擎只能在预览里播放），可以先「存到相册」再用系统图库设成静态壁纸"
            )
        }
        WallpaperTarget.set(context, relPath, kind, parallax, transform)
        return runCatching {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, GalleryWallpaperService::class.java)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result(true, "请在系统弹出的预览里点「设置壁纸」")
        }.getOrElse {
            Result(
                false,
                "这台手机没有系统动态壁纸选择器（部分定制系统会移除），已把这张图记为壁纸目标；" +
                    "可以改用「存到相册」后在桌面长按设为壁纸"
            )
        }
    }

    /** 把变换烘焙进一张屏幕尺寸的位图（只用于省电模式）。 */
    private fun renderStatic(file: File, t: WallpaperTransform, w: Int, h: Int): Bitmap? = runCatching {
        val src = decodeSampled(file, w) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        if (placeFrame(canvas, src.width.toFloat(), src.height.toFloat(), w, h, t)) {
            canvas.drawBitmap(
                src,
                null,
                RectF(-src.width / 2f, -src.height / 2f, src.width / 2f, src.height / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
        }
        canvas.restore()
        src.recycle()
        out
    }.getOrNull()

    /** 按最长边目标宽度采样解码，避免 4000×6000 的原图直接 OOM。 */
    private fun decodeSampled(file: File, targetW: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    /** 静态壁纸在 API 24 才有 FLAG 参数，这里做一次兜底（minSdk 24 实际恒为真）。 */
    fun supportsFlags(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
