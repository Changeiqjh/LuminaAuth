package com.luminaauth.theme

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.luminaauth.LiquidBottomTab
import com.luminaauth.LiquidBottomTabs
import com.luminaauth.PrefUtils
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ThemeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaAuthTheme {
                ThemeSettingsScreen(onBack = { finish() })
            }
        }
    }
}

/** 正在编辑的配色项（dark 区分浅色 / 深色） */
private data class EditTarget(val role: ColorRole, val dark: Boolean)

@Composable
private fun ThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val surfaceColor = MiuixTheme.colorScheme.surface

    // 保存 / 重置后自增，强制两个色样重新读取持久化值
    var refresh by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    var themeMode by remember { mutableIntStateOf(PrefUtils.getThemeMode(context)) }
    val activity = context as? Activity
    fun applyMode(index: Int) {
        themeMode = index
        PrefUtils.setThemeMode(context, index)
        // 与原设置页一致：延迟重建，让分段控件动画先收尾
        Handler(Looper.getMainLooper()).postDelayed({ activity?.recreate() }, 300)
    }

    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "主题与配色",
                color = surfaceColor,
                titleColor = onSurface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回", tint = onSurface)
                    }
                },
            )
        },
        popupHost = { },
        containerColor = MiuixTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // —— 主题模式 ——
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("主题模式", fontSize = 15.sp, color = onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text("跟随系统 / 浅色 / 深色，全界面自动适配", fontSize = 12.sp, color = secondary)
                        Spacer(Modifier.height(12.dp))
                        LiquidBottomTabs(
                            selectedTabIndex = { themeMode },
                            onTabSelected = ::applyMode,
                            backdrop = canvasBackdrop,
                            tabsCount = 3,
                            onDragStateChange = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            LiquidBottomTab(onClick = { applyMode(0) }) {
                                Text("跟随系统", fontSize = 13.sp, color = onSurface)
                            }
                            LiquidBottomTab(onClick = { applyMode(1) }) {
                                Text("浅色", fontSize = 13.sp, color = onSurface)
                            }
                            LiquidBottomTab(onClick = { applyMode(2) }) {
                                Text("深色", fontSize = 13.sp, color = onSurface)
                            }
                        }
                    }
                }
            }

            // —— 每一类元素一组独立调色板 ——
            PaletteCategory.entries.forEach { category ->
                item(key = "header_${category.name}") {
                    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                        Text(category.title, fontSize = 14.sp, color = onSurface)
                        Text(category.subtitle, fontSize = 12.sp, color = secondary)
                    }
                }
                item(key = "card_${category.name}") {
                    val roles = PaletteRoles.byCategory(category)
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            roles.forEachIndexed { index, role ->
                                ColorRoleRow(
                                    role = role,
                                    refresh = refresh,
                                    onPick = { dark -> editTarget = EditTarget(role, dark) },
                                )
                                if (index != roles.lastIndex) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(1.dp)
                                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        resetCategory(context, category)
                                        refresh++
                                    }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("恢复本类默认", fontSize = 13.sp, color = secondary)
                            }
                        }
                    }
                }
            }

            // —— 全部重置 ——
            item(key = "reset_all") {
                Button(
                    onClick = {
                        PrefUtils.resetAllPaletteColors(context)
                        refresh++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    top.yukonga.miuix.kmp.basic.Text("全部恢复默认配色")
                }
            }
        }
    }

    // 取色对话框
    editTarget?.let { target ->
        val title = if (target.role.mode == ColorVariantMode.LIGHT_DARK) {
            target.role.label + if (target.dark) " · 深色" else " · 浅色"
        } else {
            target.role.label
        }
        AppColorPickerDialog(
            show = true,
            title = title,
            initialColor = roleColor(context, target.role, target.dark),
            onDismiss = { editTarget = null },
            onSave = { color ->
                val argb = color.toArgb().toLong() and 0xFFFFFFFFL
                PrefUtils.setPaletteColor(context, target.role.prefKey(target.dark), argb)
                editTarget = null
                refresh++
            },
        )
    }
}

@Composable
private fun ColorRoleRow(
    role: ColorRole,
    refresh: Int,
    onPick: (dark: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    // refresh 变化时重新从持久化读取
    val lightColor = remember(refresh, role) { roleColor(context, role, false) }
    val darkColor = if (role.mode == ColorVariantMode.LIGHT_DARK) {
        remember(refresh, role) { roleColor(context, role, true) }
    } else {
        null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(role.label, fontSize = 15.sp, color = onSurface)
            if (role.mode == ColorVariantMode.LIGHT_DARK) {
                Text("浅色、深色可分别设置", fontSize = 12.sp, color = secondary)
            }
        }
        if (darkColor == null) {
            ColorSwatch(color = lightColor, onClick = { onPick(false) })
        } else {
            SwatchWithLabel(label = "浅", color = lightColor, onClick = { onPick(false) })
            Spacer(Modifier.width(14.dp))
            SwatchWithLabel(label = "深", color = darkColor, onClick = { onPick(true) })
        }
    }
}

@Composable
private fun SwatchWithLabel(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ColorSwatch(color = color, onClick = onClick)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
    }
}

@Composable
private fun ColorSwatch(color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(onClick = onClick)
    )
}
