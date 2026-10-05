package com.luminaauth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面底部模糊栏（[TopBlurBar] 的底部镜像）：
 * 从屏幕最底部开始的渐进式模糊，模糊强度沿向上方向渐隐到透明，
 * 内容滚动到底部 Dock 下方时从模糊中穿过。
 *
 * 复用页面自有的 layerBackdrop，不支持 RuntimeShader 的设备降级为纯色 surface。
 */
@Composable
fun BottomBlurBar(
    backdrop: Backdrop?,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (blurEnabled && backdrop != null) {
                    Modifier.progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = BOTTOM_BAR_BLUR_RADIUS,
                        gradient = BOTTOM_BAR_PROGRESSIVE_BLUR,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = surfaceColor.copy(alpha = BOTTOM_BAR_SURFACE_ALPHA))
                            )
                        ),
                    )
                } else {
                    Modifier.background(surfaceColor)
                }
            ),
    ) {
        content()
    }
}

// 与顶栏 TOP_BAR_PROGRESSIVE_BLUR 镜像（ProgressiveBlur.Bottom）
private val BOTTOM_BAR_PROGRESSIVE_BLUR = ProgressiveBlur.Bottom.copy(
    startFraction = 0.12f,
    endFraction = 1f,
    curve = 1.25f,
)
private const val BOTTOM_BAR_BLUR_RADIUS = 16f
private const val BOTTOM_BAR_SURFACE_ALPHA = 0.66f
