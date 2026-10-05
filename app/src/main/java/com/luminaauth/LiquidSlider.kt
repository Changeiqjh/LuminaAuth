// 液态玻璃滑块 — 原版视觉：
// 拖动时 snap 让 thumb 实时跟手，速度驱动横向拉伸（Q 弹）；
// 松手后 release() 把长宽高回弹默认，并按 valueSnap 吸附最终值
package com.luminaauth

import com.luminaauth.theme.LocalAppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    // 松手时的值吸附（如等待时间吸附到整数秒）；与当前值不同时带动画回弹
    valueSnap: (Float) -> Float = { it },
) {
    // 跟随应用主题（跟随系统/浅色/深色）：
    // 跟随系统时用系统深浅兜底，浅色/深色时用应用内设置
    val context = LocalContext.current
    val isLightTheme = (ThemeUtils.getIsDark(context) ?: isSystemInDarkTheme()) != true
    val appColors = LocalAppColors.current
    // 禁用时已填充轨道用轨道灰色
    val accentColor = appColors.controlAccent
    val trackColor = appColors.controlTrack.copy(alpha = if (isLightTheme) 0.2f else 0.36f)
    val trackBackdrop = rememberLayerBackdrop()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val trackWidth = constraints.maxWidth
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        // 是否正在拖拽：拖拽中由 snap 直接驱动 value，弹簧回流不干预，避免动画竞争
        var isDragging by remember { mutableStateOf(false) }
        val damped = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }
        LaunchedEffect(damped) {
            snapshotFlow { value() }.collectLatest { v ->
                // 拖拽中 thumb 已由 snap 实时驱动，此处跳过；松手后外部改动才走弹簧
                if (!isDragging && damped.targetValue != v) damped.updateValue(v)
            }
        }
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .pointerInput(enabled, trackWidth) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { position ->
                            val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                            val targetValue = (if (isLtr) valueRange.start + delta else valueRange.endInclusive - delta)
                                .fastCoerceIn(valueRange.start, valueRange.endInclusive)
                            damped.animateToValue(targetValue)
                            onValueChange(targetValue)
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth()
            )
            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * damped.progress).fastRoundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = (-size.width / 2f + trackWidth * damped.progress)
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                // key 用稳定值（enabled / trackWidth），绝不把每次重组新建的 valueRange 当 key
                .pointerInput(enabled, trackWidth) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // touch slop + 水平方向确认后才触发，不会按下即锁死页面
                            isDragging = true
                            damped.press()
                            onDragStateChange?.invoke(true)
                            didDrag = false
                        },
                        onDragEnd = {
                            isDragging = false
                            // 直接取手指最后位置（snap 已让 targetValue = 手指位置），
                            // 不依赖滞后弹簧 → 不会回原值
                            val finalValue = damped.targetValue
                            val snapped = valueSnap(finalValue)
                            // 位置需要吸附（整数秒）时以弹簧回弹
                            if (snapped != finalValue) damped.updateValue(snapped)
                            onValueChange(snapped)
                            // 松手后尺寸（长宽高）回弹默认
                            damped.release()
                            onDragStateChange?.invoke(false)
                        },
                        onDragCancel = {
                            isDragging = false
                            damped.release()
                            onDragStateChange?.invoke(false)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        didDrag = true
                        // thumb 实时跟手：每帧直接 snap 到换算后的位置
                        val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount / trackWidth)
                        val newValue = (if (isLtr) damped.value + delta else damped.value - delta)
                            .fastCoerceIn(valueRange.start, valueRange.endInclusive)
                        damped.snapToValue(newValue)
                        onValueChange(newValue)
                    }
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = damped.pressProgress
                            scale(lerp(2f / 3f, 1f, progress), lerp(0f, 1f, progress)) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = damped.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = {
                        val progress = damped.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val progress = damped.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        // 按压 / 拖动中放大 + 速度横向拉伸，仅松手后由 release() 回弹默认长宽高
                        scaleX = damped.scaleX
                        scaleY = damped.scaleY
                        // 官方同款除数 /10f（原项目 /50f 形变弱 5 倍）
                        val velocity = damped.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 1f - damped.pressProgress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
