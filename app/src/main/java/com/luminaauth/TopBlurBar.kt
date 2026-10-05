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
 * 页面顶部模糊栏（与 HyperIsland 的 BlurredBar(topGradient = true) 同款）：
 * 从屏幕最顶部开始的渐进式模糊，模糊强度沿向下方向渐隐到透明，
 * 内容滚动时从模糊栏下方穿过。
 *
 * 复用主界面 Pager 上的 layerBackdrop（与底部液态玻璃 Dock 同一 backdrop），
 * 不支持 RuntimeShader 的设备降级为纯色 surface。
 */
@Composable
fun TopBlurBar(
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
                        blurRadius = TOP_BAR_BLUR_RADIUS,
                        gradient = TOP_BAR_PROGRESSIVE_BLUR,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = surfaceColor.copy(alpha = TOP_BAR_SURFACE_ALPHA))
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

// HyperIsland BlurBars.kt 中顶栏的同款参数
private val TOP_BAR_PROGRESSIVE_BLUR = ProgressiveBlur.Top.copy(
    startFraction = 0.12f,
    endFraction = 1f,
    curve = 1.25f,
)
private const val TOP_BAR_BLUR_RADIUS = 16f
private const val TOP_BAR_SURFACE_ALPHA = 0.66f
