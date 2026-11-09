package com.mangashelf.reader.data

import android.content.Context

/**
 * 当前壁纸指向的图片与渲染参数（WallpaperService 启动时读取）。
 *
 * 只存 relPath 与 kind，不存绝对路径 —— 应用重装或数据目录迁移后路径会变。
 *
 * v1.6.5 起多存三项：
 * - [parallax] 视差开关（统一走 WallpaperService 后，这一项才真正可控）
 * - [transform] 编辑页的裁剪变换（见 [WallpaperTransform]）
 * - [powerSave] 用户选的「省电模式（系统静态壁纸）」，只影响下次设置时的路径，不影响本服务
 */
object WallpaperTarget {

    private const val PREFS = "wallpaper_prefs"
    private const val KEY_PATH = "rel_path"
    private const val KEY_KIND = "kind"
    private const val KEY_PARALLAX = "parallax"
    private const val KEY_TRANSFORM = "transform"
    private const val KEY_POWER_SAVE = "power_save"

    data class Entry(
        val relPath: String,
        val kind: AnimationKind,
        val parallax: Boolean = true,
        val transform: WallpaperTransform = WallpaperTransform.DEFAULT
    )

    fun set(
        context: Context,
        relPath: String,
        kind: AnimationKind,
        parallax: Boolean = true,
        transform: WallpaperTransform = WallpaperTransform.DEFAULT
    ) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PATH, relPath)
                .putString(KEY_KIND, kind.name)
                .putBoolean(KEY_PARALLAX, parallax)
                .putString(KEY_TRANSFORM, transform.normalized().toJson())
                .apply()
        }
    }

    fun clear(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    fun current(context: Context): Entry? = runCatching {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = sp.getString(KEY_PATH, null) ?: return@runCatching null
        Entry(
            relPath = path,
            kind = AnimationKind.fromName(sp.getString(KEY_KIND, null)),
            // 老数据没有这两个 key：默认开启视差 + 默认变换，与 v1.6.4 观感一致
            parallax = sp.getBoolean(KEY_PARALLAX, true),
            transform = WallpaperTransform.fromJson(sp.getString(KEY_TRANSFORM, null))
        )
    }.getOrNull()

    /** 是否使用系统静态壁纸（省电模式）。设置页里的开关，只影响下一次「设为壁纸」。 */
    fun powerSave(context: Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_POWER_SAVE, false)
    }.getOrDefault(false)

    fun setPowerSave(context: Context, on: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_POWER_SAVE, on)
                .apply()
        }
    }
}
