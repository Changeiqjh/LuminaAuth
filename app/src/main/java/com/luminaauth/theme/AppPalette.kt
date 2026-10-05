package com.luminaauth.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.luminaauth.PrefUtils
import com.luminaauth.ThemeUtils
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全局配色唯一数据源。
 *
 * 每一类元素对应一个 [PaletteCategory]（独立调色板），分类下的每一项是一个 [ColorRole]。
 * 所有界面颜色都只允许从这里取值，不再允许在各 Composable 里写死十六进制色值。
 * 默认色板沿用原 HyperOS / MIUI 风格，可在「设置 → 主题与配色」页逐项自定义并持久化。
 */

/** 配色分类：每一类元素一组独立调色板 */
enum class PaletteCategory(val title: String, val subtitle: String) {
    THEME("主题色", "Miuix 主题种子色 / 全局强调色"),
    STATUS("状态色", "成功、警告、危险等状态反馈"),
    GLASS("液态玻璃", "Dock、开关、滑块等玻璃控件配色"),
}

/** 配色项是否区分浅色 / 深色两套取值 */
enum class ColorVariantMode { SINGLE, LIGHT_DARK }

/**
 * 一个可编辑的配色项。
 *
 * @param key       持久化标识（SharedPreferences key 前缀）
 * @param label     设置页显示名
 * @param mode      SINGLE = 深浅共用一色；LIGHT_DARK = 浅色 / 深色各一色
 * @param defaultLight 浅色（或 SINGLE）默认 ARGB
 * @param defaultDark  深色默认 ARGB
 */
@Stable
class ColorRole(
    val key: String,
    val label: String,
    val category: PaletteCategory,
    val mode: ColorVariantMode,
    val defaultLight: Long,
    val defaultDark: Long = defaultLight,
) {
    fun prefKey(dark: Boolean): String =
        if (mode == ColorVariantMode.LIGHT_DARK) {
            "palette_${key}_${if (dark) "dark" else "light"}"
        } else {
            "palette_$key"
        }

    fun defaultArgb(dark: Boolean): Long = if (dark) defaultDark else defaultLight

    /** 需要持久化的全部 key（SINGLE 一个，LIGHT_DARK 两个） */
    val prefKeys: List<String>
        get() = if (mode == ColorVariantMode.LIGHT_DARK) listOf(prefKey(false), prefKey(true))
        else listOf(prefKey(false))
}

/** 配色项注册表：新增 / 修改颜色只动这里 */
object PaletteRoles {
    // —— 主题色 ——
    val ACCENT = ColorRole(
        key = "accent",
        label = "主题强调色",
        category = PaletteCategory.THEME,
        mode = ColorVariantMode.SINGLE,
        defaultLight = 0xFF3482FF,
    )

    // —— 状态色 ——
    val SUCCESS = ColorRole(
        key = "success",
        label = "成功（已开启 / 已连接）",
        category = PaletteCategory.STATUS,
        mode = ColorVariantMode.SINGLE,
        defaultLight = 0xFF43A047,
    )
    val WARNING = ColorRole(
        key = "warning",
        label = "警告（未连接 / 提醒）",
        category = PaletteCategory.STATUS,
        mode = ColorVariantMode.SINGLE,
        defaultLight = 0xFFFF8F00,
    )
    val DANGER = ColorRole(
        key = "danger",
        label = "危险（重置 / 删除）",
        category = PaletteCategory.STATUS,
        mode = ColorVariantMode.SINGLE,
        defaultLight = 0xFFFF4444,
    )

    // —— 液态玻璃 ——
    val CONTROL_ACCENT = ColorRole(
        key = "control_accent",
        label = "控件强调色",
        category = PaletteCategory.GLASS,
        mode = ColorVariantMode.LIGHT_DARK,
        defaultLight = 0xFF3482FF,
        defaultDark = 0xFF3482FF,
    )
    val GLASS_CONTAINER = ColorRole(
        key = "glass_container",
        label = "玻璃容器底色",
        category = PaletteCategory.GLASS,
        mode = ColorVariantMode.LIGHT_DARK,
        defaultLight = 0xFFFAFAFA,
        defaultDark = 0xFF121212,
    )
    val CONTENT_ON_GLASS = ColorRole(
        key = "content_on_glass",
        label = "玻璃上图标 / 文字",
        category = PaletteCategory.GLASS,
        mode = ColorVariantMode.LIGHT_DARK,
        defaultLight = 0xFF3A3A3A,
        defaultDark = 0xFFFFFFFF,
    )
    val CONTROL_TRACK = ColorRole(
        key = "control_track",
        label = "滑块 / 开关轨道色",
        category = PaletteCategory.GLASS,
        mode = ColorVariantMode.LIGHT_DARK,
        defaultLight = 0xFF787878,
        defaultDark = 0xFF787880,
    )

    /** 全部配色项（设置页遍历用） */
    val all: List<ColorRole> = listOf(
        ACCENT,
        SUCCESS, WARNING, DANGER,
        CONTROL_ACCENT, GLASS_CONTAINER, CONTENT_ON_GLASS, CONTROL_TRACK,
    )

    fun byCategory(category: PaletteCategory): List<ColorRole> =
        all.filter { it.category == category }
}

/** 解析后的当前主题配色（Compose 直接消费） */
@Stable
data class AppColors(
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val controlAccent: Color,
    val glassContainer: Color,
    val contentOnGlass: Color,
    val controlTrack: Color,
)

/** 不依赖持久化的默认配色（也用作 CompositionLocal 的兜底） */
fun defaultAppColors(isDark: Boolean): AppColors = AppColors(
    accent = Color(PaletteRoles.ACCENT.defaultArgb(isDark).toInt()),
    success = Color(PaletteRoles.SUCCESS.defaultArgb(isDark).toInt()),
    warning = Color(PaletteRoles.WARNING.defaultArgb(isDark).toInt()),
    danger = Color(PaletteRoles.DANGER.defaultArgb(isDark).toInt()),
    controlAccent = Color(PaletteRoles.CONTROL_ACCENT.defaultArgb(isDark).toInt()),
    glassContainer = Color(PaletteRoles.GLASS_CONTAINER.defaultArgb(isDark).toInt()),
    contentOnGlass = Color(PaletteRoles.CONTENT_ON_GLASS.defaultArgb(isDark).toInt()),
    controlTrack = Color(PaletteRoles.CONTROL_TRACK.defaultArgb(isDark).toInt()),
)

/** 读取单个配色项在指定深浅模式下的实际颜色（含用户自定义），设置页两个色样都要显示时使用 */
fun roleColor(context: Context, role: ColorRole, isDark: Boolean): Color =
    Color(PrefUtils.getPaletteColor(context, role.prefKey(isDark), role.defaultArgb(isDark)).toInt())

/** 从持久化配置解析出当前主题的完整配色 */
fun resolveAppColors(context: Context, isDark: Boolean): AppColors = AppColors(
    accent = roleColor(context, PaletteRoles.ACCENT, isDark),
    success = roleColor(context, PaletteRoles.SUCCESS, isDark),
    warning = roleColor(context, PaletteRoles.WARNING, isDark),
    danger = roleColor(context, PaletteRoles.DANGER, isDark),
    controlAccent = roleColor(context, PaletteRoles.CONTROL_ACCENT, isDark),
    glassContainer = roleColor(context, PaletteRoles.GLASS_CONTAINER, isDark),
    contentOnGlass = roleColor(context, PaletteRoles.CONTENT_ON_GLASS, isDark),
    controlTrack = roleColor(context, PaletteRoles.CONTROL_TRACK, isDark),
)

/** 重置某一个配色项（SINGLE 清一个，LIGHT_DARK 清两个） */
fun resetRole(context: Context, role: ColorRole) {
    role.prefKeys.forEach { PrefUtils.resetPaletteColor(context, it) }
}

/** 重置某一分类下的全部配色 */
fun resetCategory(context: Context, category: PaletteCategory) {
    PaletteRoles.byCategory(category).forEach { resetRole(context, it) }
}

/** 全局 CompositionLocal：Composable 一律通过 LocalAppColors.current 取色 */
val LocalAppColors = compositionLocalOf { defaultAppColors(isDark = false) }

private const val PREFS_NAME = "app_prefs"
private const val KEY_THEME_MODE = "theme_mode"

/**
 * 统一主题包装器：三个 Activity（主界面 / 高级设置 / 关于 / 主题配色）都套它，
 * 负责根据主题模式 + 自定义调色板构建 MiuixTheme，并通过 [LocalAppColors] 下发配色。
 * 配色或主题模式变化时自动重组 / 重建控制器，无需手动 recreate 即可即时生效。
 */
@Composable
fun LuminaAuthTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // 监听调色板 / 主题模式写入，触发重组
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = PrefUtils.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME_MODE || (key != null && key.startsWith(PrefUtils.PALETTE_KEY_PREFIX))) {
                tick++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val mode = remember(tick) { PrefUtils.getThemeMode(context) }
    val isDarkNullable: Boolean? = remember(tick) { ThemeUtils.getIsDark(context) }
    val systemDark = isSystemInDarkTheme()
    val isDark = isDarkNullable ?: systemDark

    // 状态栏 / 导航栏图标随明暗切换：深色模式用浅色图标，浅色模式用深色图标，
    // 并把系统栏置透明，让 App 自身背景透出来，避免深色图标压在深色背景上看不见。
    val view = LocalView.current
    val activity = context as? android.app.Activity
    DisposableEffect(isDark, view) {
        val window = activity?.window
        if (window != null) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    val appColors = remember(tick, isDark) { resolveAppColors(context, isDark) }

    val controller = remember(tick, mode, isDark) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.MonetSystem,
            keyColor = appColors.accent,
            isDark = isDarkNullable,
        )
    }

    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalAppColors provides appColors, content = content)
    }
}
