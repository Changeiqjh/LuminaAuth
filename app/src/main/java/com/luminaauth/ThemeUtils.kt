package com.luminaauth

import android.content.Context

object ThemeUtils {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    // 返回 null=跟随系统，false=浅色，true=深色
    fun getIsDark(context: Context): Boolean? {
        return when (PrefUtils.getThemeMode(context)) {
            THEME_LIGHT -> false
            THEME_DARK -> true
            else -> null
        }
    }

    fun getThemeName(context: Context): String {
        return when (PrefUtils.getThemeMode(context)) {
            THEME_LIGHT -> "浅色"
            THEME_DARK -> "深色"
            else -> "跟随系统"
        }
    }
}
