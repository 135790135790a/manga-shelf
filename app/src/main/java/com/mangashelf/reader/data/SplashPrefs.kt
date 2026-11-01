package com.mangashelf.reader.data

import android.content.Context

/**
 * v1.5.9：开场界面（鲸鱼娘书架）的展示时长。
 *
 * 档位固定 0 / 1 / 2 / 3 / 5 秒，默认 2 秒。
 * 0 秒 = 完全不渲染开场层，冷启动只剩系统 windowBackground 那一帧（无白屏）。
 *
 * 存储用独立的 SharedPreferences（`splash_prefs`），不和主题、护眼混在一起，
 * 免得「恢复默认设置」里动一处牵连三处。
 */
object SplashPrefs {

    private const val PREFS = "splash_prefs"
    private const val KEY_MS = "duration_ms"

    /** 可选档位（毫秒） */
    val OPTIONS = listOf(0, 1000, 2000, 3000, 5000)

    const val DEFAULT_MS = 2000

    fun load(context: Context): Int {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MS, DEFAULT_MS)
        }.getOrDefault(DEFAULT_MS)
        return if (raw in OPTIONS) raw else DEFAULT_MS
    }

    fun save(context: Context, ms: Int) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MS, if (ms in OPTIONS) ms else DEFAULT_MS)
                .apply()
        }
    }

    fun label(ms: Int): String = when (ms) {
        0 -> "0 秒（不显示）"
        in 1000..5999 -> "${ms / 1000} 秒"
        else -> "${DEFAULT_MS / 1000} 秒"
    }
}
