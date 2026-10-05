package com.luminaauth.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.luminaauth.LiquidBottomTab
import com.luminaauth.LiquidBottomTabs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用液态玻璃分段 Dock：主题深浅色切换与插件 ISP 选择共用同一套实现
 * （底层就是 [LiquidBottomTabs] / [LiquidBottomTab]）。
 *
 * @param options 分段文本，顺序即下标顺序。
 * @param selectedIndex 当前选中下标，越界时收敛到合法范围。
 * @param onSelect 点击/滑动切换回调，参数为新的下标。
 * @param backdrop 玻璃取样背景；不传则退化为用当前 surface 色自绘。
 */
@Composable
fun SegmentedDock(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    fontSize: TextUnit = 13.sp,
    contentColor: Color? = null,
) {
    if (options.isEmpty()) return
    val index = selectedIndex.coerceIn(0, options.lastIndex)
    val surfaceColor = MiuixTheme.colorScheme.surface
    // 未显式传入背景时用 surface 色兜底，保证玻璃质感不至于失真
    val fallbackBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    val textColor = contentColor ?: MiuixTheme.colorScheme.onSurface
    LiquidBottomTabs(
        selectedTabIndex = { index },
        onTabSelected = { i -> if (i in options.indices) onSelect(i) },
        backdrop = backdrop ?: fallbackBackdrop,
        tabsCount = options.size,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        onDragStateChange = onDragStateChange,
    ) {
        options.forEachIndexed { i, label ->
            LiquidBottomTab(onClick = { onSelect(i) }) {
                Text(
                    text = label,
                    fontSize = fontSize,
                    maxLines = 1,
                    color = textColor,
                )
            }
        }
    }
}