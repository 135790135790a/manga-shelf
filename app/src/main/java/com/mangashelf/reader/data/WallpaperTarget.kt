package com.mangashelf.reader.data

import android.content.Context

/**
 * 当前动态壁纸指向的图片（跨进程可读：壁纸服务跑在 system_server 的客户端进程里，
 * 但 WallpaperService 实例本身仍在本应用进程，所以普通 SharedPreferences 就够）。
 *
 * 只存 relPath 与 kind，不存绝对路径 —— 应用重装或数据目录迁移后路径会变。
 */
object WallpaperTarget {

    private const val PREFS = "wallpaper_prefs"
    private const val KEY_PATH = "rel_path"
    private const val KEY_KIND = "kind"

    data class Entry(val relPath: String, val kind: AnimationKind)

    fun set(context: Context, relPath: String, kind: AnimationKind) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PATH, relPath)
                .putString(KEY_KIND, kind.name)
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
        val kind = AnimationKind.fromName(sp.getString(KEY_KIND, null))
        Entry(path, kind)
    }.getOrNull()
}
