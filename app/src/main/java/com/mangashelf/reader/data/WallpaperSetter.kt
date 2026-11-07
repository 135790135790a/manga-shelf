package com.mangashelf.reader.data

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.mangashelf.reader.service.GalleryWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把图片工作栏里的一张图设为壁纸。
 *
 * 系统壁纸（[android.app.WallpaperManager]）只接受静态 Bitmap，
 * 所以静态图直接设置；六类动图都改成「写入目标 + 唤起系统动态壁纸选择器」，
 * 由 [GalleryWallpaperService] 逐帧绘制。
 */
object WallpaperSetter {

    data class Result(val ok: Boolean, val message: String)

    /** 是否走动态壁纸。只有静态图能直接铺成系统壁纸。 */
    fun isLive(kind: AnimationKind): Boolean = kind != AnimationKind.STATIC

    suspend fun applyStill(context: Context, file: File): Result = withContext(Dispatchers.IO) {
        runCatching {
            val metrics = context.resources.displayMetrics
            val targetW = maxOf(metrics.widthPixels, 720)
            val bitmap = decodeSampled(file, targetW)
                ?: return@runCatching Result(false, "这张图片解码失败，没法设为壁纸")
            WallpaperManager.getInstance(context).setBitmap(
                bitmap,
                null,
                true,
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
            Result(true, "已设为壁纸（桌面与锁屏）")
        }.getOrElse { e ->
            Result(false, "设置壁纸失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 动图：先把目标写进 SharedPreferences，再唤起系统「动态壁纸」确认框。
     * 部分国产 ROM 没有这个 Activity，此时给出明确降级提示，而不是静默失败。
     */
    fun applyLive(context: Context, relPath: String, kind: AnimationKind): Result {
        // SVGA 的动画只能由 SVGAImageView 驱动，壁纸服务里没有 View，2.6.1 也没有手动推进接口。
        // 与其设一张黑屏，不如直接说清楚。
        if (kind == AnimationKind.SVGA) {
            return Result(false, "SVGA 动画暂不支持设为壁纸（引擎只能在图片预览里播放），可以先「存到相册」再用系统图库设静态壁纸")
        }
        WallpaperTarget.set(context, relPath, kind)
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
