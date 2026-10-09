package com.luminaauth
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.luminaauth.theme.LuminaAuthTheme
import com.luminaauth.theme.LocalAppColors
import com.luminaauth.ui.components.SegmentedDock
import com.luminaauth.plugin.Plugin
import com.luminaauth.plugin.PluginManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        @JvmField
        var foreground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppContext.init(this)
        super.onCreate(savedInstanceState)
        // 每次启动自动重绑通知监听：
        // 前提 = 通知检测模式已开启 + 已获得通知使用权。
        // 解决国产 ROM 上进程被杀/重启后通知监听 IPC 断开、收不到网关通知的问题。
        if (PrefUtils.isNotifyDetectEnabled(this)
            && GatewayNotificationListener.isAccessGranted(this)
            && !GatewayNotificationListener.isListenerConnected()
        ) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    GatewayNotificationListener.requestRebind(applicationContext)
                } catch (e: Exception) {
                    LogBuffer.addDetail("NTFY", "launch rebind_err=" + e.message)
                }
            }, 1200)
        }
        setContent {
            LuminaAuthTheme {
                SchoolAutologinApp()
            }
        }
        // ④ 解锁高刷新率：关闭系统按需降帧的省电均衡，并把窗口首选刷新率提到屏幕最高档
        applyHighRefreshRate()
    }

    /**
     * ④ 高刷采样：让本页面按屏幕刷新率渲染，而不是被系统按 60Hz 降帧（只碰窗口动画/绘制参数，不动业务逻辑）。
     * - Android 15+：关闭「省电帧率均衡」+ 触摸时提升帧率（Window 级开关）；
     * - Android 11+：把窗口首选刷新率/显示模式指向「与当前分辨率相同」的最高刷新率模式；
     * - Android 15+：再用 View 级 setRequestedFrameRate 把期望帧率告知系统。
     */
    private fun applyHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                // 系统的按需降帧省电策略会把翻页动画锁在 60Hz，这里显式关掉
                window.setFrameRatePowerSavingsBalanced(false)
                window.setFrameRateBoostOnTouchEnabled(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = window.decorView.display ?: return
                val current = display.mode
                // 只在同分辨率里挑最高刷新率，避免切到低分辨率的高刷模式
                val best = display.supportedModes
                    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                    .maxByOrNull { it.refreshRate } ?: current
                if (best.refreshRate > current.refreshRate) {
                    val attrs = window.attributes
                    attrs.preferredRefreshRate = best.refreshRate
                    attrs.preferredDisplayModeId = best.modeId
                    window.attributes = attrs
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    window.decorView.setRequestedFrameRate(best.refreshRate)
                }
                LogBuffer.add("UI", "high_refresh rate=" + best.refreshRate)
            }
        } catch (e: Exception) {
            LogBuffer.addDetail("UI", "high_refresh_err=" + e.message)
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        // 兜底：自动认证已开启但前台服务不在运行时，打开 App 即重新拉起服务
        try {
            if (PrefUtils.isAutoAuthEnabled(this) && !isAutoLoginServiceRunning()) {
                val intent = Intent(this, AutoLoginService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
                LogBuffer.add("UI", "launch svc=restart")
            }
        } catch (e: Exception) {
            LogBuffer.add("UI", "launch svc=restart_fail err=" + e.message)
        }
    }

    override fun onStop() {
        super.onStop()
        foreground = false
    }

    /** 检查自动认证前台服务是否在运行（Android 8+ 仅返回本应用自己的服务，正好适用） */
    private fun isAutoLoginServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Int.MAX_VALUE).any {
                AutoLoginService::class.java.name == it.service?.className
            }
        } catch (e: Exception) {
            false
        }
    }
}

// 手动登录防连点：一次只允许一个手动登录在途（跨重组共享）
private val manualInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

// ==================== 主界面 ====================
@Composable
fun SchoolAutologinApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 3 }
    val mainPagerState = rememberMainPagerState(pagerState)
    val surfaceColor = MiuixTheme.colorScheme.surface
    // Dock 图标/文字颜色：取自统一调色板「玻璃上图标/文字」，深色玻璃为白、浅色玻璃为深灰，
    // 避免任何组合下出现深色图标不可见
    val appColors = LocalAppColors.current
    val dockIconColor = appColors.contentOnGlass
    // 关键：仅在设备支持 RenderEffect（Android 12+ 且 GPU 支持 RuntimeShader）时才启用 blur，
    // 不支持的设备走纯色降级，避免渲染崩溃（SukiSU 同款防护）
    val blurSupported = remember { isRenderEffectSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 手动滑动时同步 Dock 高亮
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { mainPagerState.syncPage() }
    }

    // ---- 应用状态 ----
    // Active plugin drives the login form, ISP options and request flow.
    val plugin = remember { runCatching { PluginManager.active(context) }.getOrNull() }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    // ISP 选中项：优先插件声明 default: true 的条目，其次第一个
    var ispIndex by remember(plugin?.id) {
        mutableIntStateOf(plugin?.isps?.indexOfFirst { it.default }?.takeIf { it != -1 } ?: 0)
    }
    // Selected ISP suffix; falls back to the historical fixed identity "after".
    val isp = plugin?.isps?.getOrNull(ispIndex)?.id ?: "after"
    var rememberPwd by remember { mutableStateOf(false) }
    var autoAuth by remember { mutableStateOf(PrefUtils.isAutoAuthEnabled(context)) }
    var pollSec by remember { mutableFloatStateOf(PrefUtils.getPollIntervalMs(context) / 1000f) }
    var statusText by remember { mutableStateOf("状态：未登录") }
    var logVersion by remember { mutableIntStateOf(0) }
    var isControlDragging by remember { mutableStateOf(false) }

    // 恢复保存的配置：先读记住密码开关，再决定是否加载配置
    LaunchedEffect(Unit) {
        rememberPwd = PrefUtils.isRememberPwdEnabled(context)
        if (rememberPwd) {
            val saved = PrefUtils.loadConfig(context)
            if (saved != null) {
                fieldValues[Plugin.FIELD_USERNAME] = saved[0]
                fieldValues[Plugin.FIELD_PASSWORD] = saved[1]
            }
        }
    }

    // 日志监听
    LaunchedEffect(logVersion) {
        LogBuffer.setListener { logVersion++ }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        HorizontalPager(
            state = pagerState,
            // ① 预加载前后各一页，翻页时不再现算页面内容，减少切换瞬间的重绘卡顿
            beyondViewportPageCount = 1,
            // ③ 翻页 motionSpec：用 tween 缓动曲线替代默认弹簧收尾，去掉回弹抖动
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing),
            ),
            userScrollEnabled = !isControlDragging,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurSupported) Modifier.layerBackdrop(backdrop) else Modifier)
                // ② 硬件层级缓存：本层不做裁剪也不需要 renderEffect，省掉每帧的裁剪/重建开销
                .graphicsLayer {
                    renderEffect = null
                    clip = false
                }
        ) { page ->
            when (page) {
                0 -> LoginPage(
                    plugin = plugin,
                    fieldValues = fieldValues,
                    onFieldChange = { id, v -> fieldValues[id] = v },
                    ispIndex = ispIndex,
                    onIspSelect = { ispIndex = it },
                    rememberPwd = rememberPwd,
                    autoAuth = autoAuth,
                    statusText = statusText,
                    onRememberChange = {
                        rememberPwd = it
                        PrefUtils.setRememberPwdEnabled(context, it)
                        if (it) {
                            val u = fieldValues[Plugin.FIELD_USERNAME] ?: ""
                            val p = fieldValues[Plugin.FIELD_PASSWORD] ?: ""
                            if (u.isNotBlank() && p.isNotBlank()) {
                                PrefUtils.saveConfig(context, u, p, isp)
                                // 保存了新凭据：需要重新认证
                                AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                            }
                        } else {
                            PrefUtils.clearConfig(context)
                        }
                    },
                    onAutoAuthChange = { on ->
                        autoAuth = on
                        PrefUtils.setAutoAuthEnabled(context, on)
                        toggleService(context, on)
                    },
                    onLogin = {
                        val u = fieldValues[Plugin.FIELD_USERNAME] ?: ""
                        val p = fieldValues[Plugin.FIELD_PASSWORD] ?: ""
                        val missing = if (plugin != null) {
                            SchemaBuilder.firstMissing(plugin, fieldValues)
                        } else if (u.isBlank() || p.isBlank()) {
                            "missing"
                        } else {
                            null
                        }
                        if (missing != null) {
                            statusText = "请填写完整信息"
                            LogBuffer.addDetail("AUTH", "manual abort reason=empty_fields")
                        } else if (!manualInFlight.compareAndSet(false, true)) {
                            statusText = "上一次登录仍在进行，请稍候"
                            LogBuffer.addDetail("AUTH", "manual abort reason=busy")
                        } else {
                            if (rememberPwd) {
                                PrefUtils.saveConfig(context, u, p, isp)
                            }
                            statusText = "正在登录..."
                            // 手动登录：用户主动操作优先级最高，强制进入待认证状态
                            AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                            LogBuffer.addDetail("AUTH", "manual begin")
                            Thread {
                                try {
                                    val ip = LoginService.getLocalIPv4(context)
                                    if (ip == null) {
                                        statusText = "无法获取本机 IP，请连接 Wi-Fi"
                                        LogBuffer.addDetail("AUTH", "manual ip=na")
                                    } else {
                                        val status = LoginService.checkNetworkStatus(ip)
                                        LogBuffer.addDetail("AUTH", "manual ip=$ip chk=$status")
                                        if ("online" == status) {
                                            statusText = "已在线，无需登录"
                                            AuthGlobalState.setStatus(AuthStatus.AUTH_SUCCESS)
                                            LogBuffer.addDetail("AUTH", "manual already_online submit=0")
                                            LogBuffer.add("AUTH", "manual login=skip reason=online")
                                        } else if ("offline" == status) {
                                            val r = LoginService.doLogin(u, p, isp, ip)
                                            statusText = if (r.success) "登录成功" else "登录失败: " + r.message
                                            AuthGlobalState.setStatus(
                                                if (r.success) AuthStatus.AUTH_SUCCESS else AuthStatus.NEED_AUTH
                                            )
                                            val result = if (r.success) "ok" else "fail"
                                            LogBuffer.addDetail("AUTH", "manual login=$result")
                                            LogBuffer.add("AUTH", "manual login=$result")
                                        } else {
                                            statusText = "网络不可达"
                                            LogBuffer.addDetail("AUTH", "manual chk=unreachable")
                                        }
                                    }
                                } catch (e: Exception) {
                                    statusText = "异常: " + e.message
                                    LogBuffer.addDetail("AUTH", "manual err=" + e.message)
                                } finally {
                                    manualInFlight.set(false)
                                }
                            }.start()
                        }
                    },
                    onControlDrag = { isControlDragging = it }
                )
                1 -> LogPage(logVersion, blurEnabled = blurSupported && isRuntimeShaderSupported())
                2 -> SettingsPage(
                    autoAuth = autoAuth,
                    pollSec = pollSec,
                    onPollChange = { sec ->
                        pollSec = sec
                        PrefUtils.setPollIntervalMs(context, (sec * 1000).toLong())
                        AutoLoginService.restartPolling(context)
                    },
                    onAutoAuthChange = { on ->
                        autoAuth = on
                        PrefUtils.setAutoAuthEnabled(context, on)
                        toggleService(context, on)
                    },
                    onControlDrag = { isControlDragging = it },
                    // 顶部渐进模糊依赖 RuntimeShader（API33+），与 HyperIsland 同一 gate；
                    // Android 12 等不支持的设备走纯色栏，避免着色器崩溃
                    blurEnabled = blurSupported && isRuntimeShaderSupported(),
                )
            }
        }

        // 底部液态玻璃 Dock（SukiSU 同款）
        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
            selectedIndex = { mainPagerState.selectedPage },
            onSelected = { mainPagerState.animateToPage(it) },
            backdrop = backdrop,
            tabsCount = 3,
            isBlurEnabled = blurSupported,
        ) {
            val tabs = listOf(
                Triple("主页", R.drawable.ic_cottage, 0),
                Triple("日志", R.drawable.ic_article, 1),
                Triple("设置", R.drawable.ic_settings, 2),
            )
            tabs.forEach { (label, icon, index) ->
                FloatingBottomBarItem(
                    onClick = {
                        mainPagerState.animateToPage(index)
                    },
                    modifier = Modifier
                ) {
                    Icon(painter = painterResource(id = icon), contentDescription = label, modifier = Modifier.size(22.dp), tint = dockIconColor)
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                        color = dockIconColor
                    )
                }
            }
        }
    }
}

private fun toggleService(context: Context, on: Boolean) {
    val intent = Intent(context, AutoLoginService::class.java)
    if (on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
        LogBuffer.add("UI", "autoauth=on")
    } else {
        context.stopService(intent)
        LogBuffer.add("UI", "autoauth=off")
    }
}

// ==================== 主页（登录） ====================
@Composable
private fun LoginPage(
    plugin: Plugin?,
    fieldValues: SnapshotStateMap<String, String>,
    onFieldChange: (id: String, value: String) -> Unit,
    ispIndex: Int,
    onIspSelect: (Int) -> Unit,
    rememberPwd: Boolean,
    autoAuth: Boolean,
    statusText: String,
    onRememberChange: (Boolean) -> Unit,
    onAutoAuthChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onControlDrag: (Boolean) -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val cardBg = MiuixTheme.colorScheme.surfaceContainer
    val surfaceColor = MiuixTheme.colorScheme.surface
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            "校园网自动登录",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "连接校园网后自动完成认证",
            fontSize = 13.sp,
            color = secondary
        )
        Spacer(Modifier.height(24.dp))
        // 登录表单（由插件字段 schema 动态生成）
        if (plugin != null) {
            PluginFields(
                plugin = plugin,
                values = fieldValues,
                onChange = onFieldChange,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 无可用插件时的兜底表单
            TextField(
                value = fieldValues[Plugin.FIELD_USERNAME] ?: "",
                onValueChange = { onFieldChange(Plugin.FIELD_USERNAME, it) },
                modifier = Modifier.fillMaxWidth(),
                label = "用户名",
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = fieldValues[Plugin.FIELD_PASSWORD] ?: "",
                onValueChange = { onFieldChange(Plugin.FIELD_PASSWORD, it) },
                modifier = Modifier.fillMaxWidth(),
                label = "密码",
            )
        }
        // ISP 账号类型（插件驱动）—— 位置：密码输入框之后、记住密码开关之前
        // 仅声明 ≥2 个 ISP 时渲染，0/1 个整块隐藏（标题与间距一起隐藏）
        val ispList = plugin?.isps ?: emptyList()
        if (ispList.size >= 2) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "ISP账号类型",
                fontSize = 15.sp,
                color = onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            SegmentedDock(
                options = ispList.map { it.name },
                selectedIndex = ispIndex,
                onSelect = { idx -> onIspSelect(idx) },
                backdrop = canvasBackdrop,
                onDragStateChange = onControlDrag,
            )
        }
        Spacer(Modifier.height(16.dp))
        // 记住密码（miuix 卡片）
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("记住密码", fontSize = 15.sp, color = onSurface, modifier = Modifier.weight(1f))
                LiquidToggle(
                    selected = { rememberPwd },
                    onSelect = onRememberChange,
                    backdrop = canvasBackdrop,
                    onDragStateChange = onControlDrag,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 自动认证（miuix 卡片）
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动认证", fontSize = 15.sp, color = onSurface)
                    Text("连接校园网 WiFi 自动登录", fontSize = 12.sp, color = secondary)
                }
                LiquidToggle(
                    selected = { autoAuth },
                    onSelect = onAutoAuthChange,
                    backdrop = canvasBackdrop,
                    onDragStateChange = onControlDrag,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // 登录按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.primary)
                .clickable(onClick = onLogin),
            contentAlignment = Alignment.Center
        ) {
            Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            statusText,
            fontSize = 14.sp,
            color = secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(60.dp))
    }
}



@Composable
private fun LogPage(logVersion: Int, blurEnabled: Boolean) {
    val context = LocalContext.current
    val onSurface = MiuixTheme.colorScheme.onSurface
    val surfaceColor = MiuixTheme.colorScheme.surface
    val boxBg = MiuixTheme.colorScheme.surfaceContainer
    // 上半部分：运行日志
    val logText = remember(logVersion) { LogBuffer.getAllText() }
    val scrollState = rememberScrollState()
    LaunchedEffect(logVersion) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    // 下半部分：全局详细日志（全量机器日志）
    val detailLogText = remember(logVersion) { LogBuffer.getDetailAllText() }
    val detailScrollState = rememberScrollState()
    var detailAutoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(logVersion, detailAutoScroll) {
        if (detailAutoScroll) detailScrollState.scrollTo(detailScrollState.maxValue)
    }

    // 导出为文件（SAF，用户选择保存位置），替代原复制到剪贴板
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    fun writeTo(uri: Uri, text: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }
    val exportRunLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) writeTo(uri, LogBuffer.getAllText()) }
    val exportDetailLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) writeTo(uri, LogBuffer.getDetailAllText()) }

    // 本页自有 backdrop（与设置页同款拓扑）：捕获边界只包滚动内容，
    // 顶部 / 底部模糊栏在边界外，避免渲染循环闪退
    // 外层滚动状态提升到此处：滚动进行时保持渐进模糊（不冻结），
    // 滚动内容实时从模糊栏下方穿过
    val outerScrollState = rememberScrollState()
    val effectiveBlur = blurEnabled
    val pageBackdrop = if (effectiveBlur) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else null
    var topBarHeightPx by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
      // 滚动内容捕获层（不含顶部 / 底部模糊栏）
      Box(
        Modifier
            .fillMaxSize()
            .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)
      ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(outerScrollState)
                .padding(horizontal = 20.dp)
                .padding(top = with(LocalDensity.current) { topBarHeightPx.toDp() })
                .padding(bottom = 100.dp)
        ) {
        Spacer(Modifier.height(8.dp))
        // ============ 运行日志 ============
        Text("运行日志", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip("导出", Modifier.weight(1f)) {
                exportRunLauncher.launch("lumina_run_${stamp()}.log")
            }
            ActionChip("清空", Modifier.weight(1f)) {
                LogBuffer.clear()
            }
        }
        Spacer(Modifier.height(10.dp))
        // 日志框：纯背景 + 滚动文字，框顶部 / 底部不做模糊
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(boxBg)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp)
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    logText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = onSurface
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // ============ 全局详细日志（全量机器日志） ============
        Text("全局详细日志", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip(if (detailAutoScroll) "暂停滚动" else "自动滚动", Modifier.weight(1f)) {
                detailAutoScroll = !detailAutoScroll
            }
            ActionChip("导出", Modifier.weight(1f)) {
                exportDetailLauncher.launch("lumina_detail_${stamp()}.log")
            }
            ActionChip("清空", Modifier.weight(1f)) {
                LogBuffer.clearDetail()
            }
        }
        Spacer(Modifier.height(10.dp))
        // 全局详细日志框：纯背景 + 滚动文字，框顶部 / 底部不做模糊
        Box(
            Modifier
                .fillMaxWidth()
                .height(480.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(boxBg)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(detailScrollState)
                    .padding(horizontal = 12.dp)
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    detailLogText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = onSurface
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        }
      }

      // 顶部渐进模糊栏：标题不设文本，仅模糊效果；滚动进行时降级为纯色栏
      TopBlurBar(
          backdrop = pageBackdrop,
          blurEnabled = effectiveBlur,
          modifier = Modifier
              .align(Alignment.TopCenter)
              .onSizeChanged { topBarHeightPx = it.height }
      ) {
          Box(Modifier.statusBarsPadding().height(10.dp))
      }
    }
}

@Composable
private fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MiuixTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, color = accent, fontWeight = FontWeight.Medium)
    }
}

// ==================== 设置页 ====================
@Composable
private fun SettingsPage(
    autoAuth: Boolean,
    pollSec: Float,
    onPollChange: (Float) -> Unit,
    onAutoAuthChange: (Boolean) -> Unit,
    onControlDrag: (Boolean) -> Unit,
    blurEnabled: Boolean,
) {
    val context = LocalContext.current
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val appColors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    var enhancedMode by remember { mutableStateOf(PrefUtils.isEnhancedModeEnabled(context)) }
    var notifyDetect by remember { mutableStateOf(PrefUtils.isNotifyDetectEnabled(context)) }
    var waitSec by remember { mutableFloatStateOf(PrefUtils.getWaitTimeMs(context) / 1000f) }
    var hasRoot by remember { mutableStateOf(false) }
    var hasShizuku by remember { mutableStateOf(false) }
    val realVersion = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v" + pi.versionName + " · VersionCode " + pi.longVersionCode
        } catch (e: Exception) {
            "v1.1.0"
        }
    }
    // 进入设置页时检测 root 和 Shizuku 可用性
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val root = RootUtils.isRootAvailable()
            val shizuku = ShizukuUtils.isReady()
            withContext(Dispatchers.Main) {
                hasRoot = root
                hasShizuku = shizuku
            }
        }
    }
    // 权限申请
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 权限状态检测：回到前台时自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    var permTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val locGranted = remember(permTick) { isLocationGranted(context) }
    val notifGranted = remember(permTick) { isNotificationGranted(context) }
    val battGranted = remember(permTick) { isBatteryOptimizationIgnored(context) }
    val bootGranted = remember(permTick) { isAutoStartGranted(context) }
    // 通知监听权限（通知检测模式使用）：从系统授权页返回后自动刷新
    val notifyAccessGranted = remember(permTick) { GatewayNotificationListener.isAccessGranted(context) }
    // 权限被用户在系统设置手动撤销 -> 自动关闭通知检测开关并写日志
    LaunchedEffect(notifyAccessGranted) {
        if (!notifyAccessGranted && notifyDetect) {
            notifyDetect = false
            PrefUtils.setNotifyDetectEnabled(context, false)
            LogBuffer.add("UI", "notifyaccess=revoked detect=off")
            LogBuffer.addDetail("NTFY", "access=revoked detect=force_off")
        }
    }
    // 通知检测开启但监听服务未连接（被系统杀掉）-> 请求系统重新绑定恢复
    LaunchedEffect(notifyDetect, notifyAccessGranted) {
        if (notifyDetect && notifyAccessGranted && !GatewayNotificationListener.isListenerConnected()) {
            LogBuffer.addDetail("NTFY", "settings listener=0 act=rebind")
            GatewayNotificationListener.requestRebind(context)
        }
    }
    // 监听器连接状态（通知检测卡片显示）：每 3 秒轮询刷新，恢复连接后自动更新
    var listenerConnected by remember { mutableStateOf(GatewayNotificationListener.isListenerConnected()) }
    LaunchedEffect(notifyDetect, notifyAccessGranted, permTick) {
        while (notifyDetect && notifyAccessGranted) {
            listenerConnected = GatewayNotificationListener.isListenerConnected()
            delay(3000)
        }
        listenerConnected = GatewayNotificationListener.isListenerConnected()
    }
    // Shizuku 授权结果回调
    val shizukuReqCode = 1001
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                enhancedMode = true
                PrefUtils.setEnhancedModeEnabled(context, true)
                ShizukuUtils.init(context)
                LogBuffer.add("UI", "enhanced=on via=shizuku")
                // 增强模式开启：尝试提升后台进程优先级（shell 权限下尽力而为）
                AutoLoginService.boostPriorityIfEnhanced(context)
            } else {
                enhancedMode = false
                PrefUtils.setEnhancedModeEnabled(context, false)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    // 本页自有的嵌套 backdrop（HyperIsland CollapsingPage 同款）：
    // 捕获边界只包住滚动内容，顶部模糊栏在边界之外，
    // 避免模糊节点采样到含自身的 Pager 级 backdrop、形成渲染循环而闪退
    // 滚动状态提升到此处：滚动进行时保持渐进模糊（不冻结），
    // 滚动内容实时从模糊栏下方穿过
    val outerScrollState = rememberScrollState()
    val effectiveBlur = blurEnabled
    val pageBackdrop = if (effectiveBlur) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else null
    // 顶部模糊栏实测高度（含状态栏）：滚动内容据此精确避让，字号缩放也能对齐
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    // 拖动滑块/开关时：同时锁定外层 Pager 左右翻页与本页上下滚动
    var pageControlDragging by remember { mutableStateOf(false) }
    val handleControlDrag: (Boolean) -> Unit = { dragging ->
        pageControlDragging = dragging
        onControlDrag(dragging)
    }
    Box(Modifier.fillMaxSize()) {
      // 滚动内容捕获层（填充整页，不含顶部模糊栏）
      Box(
        Modifier
            .fillMaxSize()
            .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)
      ) {
        Column(
          Modifier
              .fillMaxSize()
              // 拖动滑块/开关时锁定本页上下滚动
              .verticalScroll(outerScrollState, enabled = !pageControlDragging)
              // 内容延伸到顶部模糊栏下方，滚动时从模糊中穿过（HyperIsland 同款）
              .padding(horizontal = 20.dp)
              .padding(top = with(LocalDensity.current) { topBarHeightPx.toDp() })
              .padding(bottom = 100.dp)
        ) {
        // 权限卡片（miuix 卡片，SukiSU 同款）
        Card(Modifier.fillMaxWidth()) {
            PermRow("定位权限", "识别校园网 WiFi 必需", locGranted) {
                locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            PermDivider()
            PermRow("通知权限", "后台认证状态提醒", notifGranted) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            PermDivider()
            PermRow("电池优化", "允许后台运行不被省电杀掉", battGranted) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
            PermDivider()
            PermRow("自启动", "开机后自动运行认证服务", bootGranted) {
                openAutoStartSettings(context)
            }
        }
        Spacer(Modifier.height(16.dp))
        // 检测速度卡片
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("检测速度", fontSize = 15.sp, color = onSurface, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.US, "%.1f 秒", pollSec),
                        fontSize = 14.sp,
                        color = secondary
                    )
                }
                Spacer(Modifier.height(8.dp))
                LiquidSlider(
                    value = { pollSec },
                    onValueChange = onPollChange,
                    valueRange = 0.5f..10f,
                    visibilityThreshold = 0.001f,
                    backdrop = canvasBackdrop,
                    onDragStateChange = handleControlDrag,
                )
                // 等待时间：网络就绪后、认证前的稳定等待
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                )
                Spacer(Modifier.height(16.dp))
                // 等待时间不可用时，黄色边框与「已禁用」标识共用的渐显渐隐进度：0 = 隐藏，1 = 显示
                val waitDisabled = notifyDetect || !blurEnabled
                val waitDisabledAlpha = remember { Animatable(if (waitDisabled) 1f else 0f) }
                LaunchedEffect(waitDisabled) {
                    waitDisabledAlpha.animateTo(
                        targetValue = if (waitDisabled) 1f else 0f,
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                    )
                }
                // 模糊强度保留原行为：仅通知检测模式触发
                val waitBlur = remember { Animatable(if (notifyDetect) 1f else 0f) }
                LaunchedEffect(notifyDetect) {
                    waitBlur.animateTo(
                        targetValue = if (notifyDetect) 1f else 0f,
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                    )
                }
                val waitBadgeAlpha = waitDisabledAlpha.value.coerceIn(0f, 1f)
                val waitYellow = Color(0xFFFFC107)
                // 通知检测模式：不置灰，只把「标题 + 滑块」这一层盖高斯模糊，边缘羽化表示不可用
                // 禁用态：黄色边框 + 黄色「已禁用」文字画在模糊层上层，随禁用进度淡入淡出且始终清晰
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 上下扩大模糊覆盖范围（只增加画布高度，内部滑块宽度不变、不缩进）
                        .padding(vertical = 14.dp)
                        // 黄框画在模糊层之外的外层画布上：不被模糊糊掉，始终清晰
                        .drawBehind {
                            if (waitBadgeAlpha > 0.001f) {
                                val inset = 1.dp.toPx()
                                drawRoundRect(
                                    color = waitYellow.copy(alpha = waitBadgeAlpha),
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                                    cornerRadius = CornerRadius(16.dp.toPx()),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        }
                ) {
                    // 模糊只作用在「标题 + 滑块」这一层；黄框、黄底衬、「已禁用」文字画在它上层，保持清晰不被糊掉
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (blurEnabled && waitBlur.value > 0.001f) {
                                    Modifier.blur(
                                        radiusX = 9.dp * waitBlur.value,
                                        radiusY = 9.dp * waitBlur.value,
                                        // Unbounded：模糊不被边界硬裁剪，向四周自然羽化，
                                        // 左右两端也柔和溢出（不通过加大圆角实现）
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded
                                    )
                                } else Modifier
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "等待时间",
                                fontSize = 15.sp,
                                color = onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                String.format(Locale.US, "%.0f 秒", waitSec),
                                fontSize = 14.sp,
                                color = secondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LiquidSlider(
                            value = { waitSec },
                            onValueChange = { sec ->
                                // 拖动中保持连续，滑块才跟手；数字显示 %.0f 自动取整，
                                // 持久化按整数秒写，松手再回弹吸附
                                waitSec = sec
                                PrefUtils.setWaitTimeMs(context, sec.fastRoundToInt().toLong() * 1000L)
                            },
                            valueRange = 0f..15f,
                            visibilityThreshold = 0.001f,
                            backdrop = canvasBackdrop,
                            onDragStateChange = handleControlDrag,
                            // 通知检测模式走快速通道、跳过该等待，开启时不可拖动
                            enabled = !notifyDetect,
                            // 松手回弹到整数秒
                            valueSnap = { it.fastRoundToInt().toFloat() },
                        )
                    }
                    // 不支持模糊的设备：盖一层极淡 surface 遮罩表示禁用（不发灰），跟随淡入淡出
                    if (!blurEnabled && waitBlur.value > 0.001f) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(surfaceColor.copy(alpha = 0.4f * waitBlur.value))
                        )
                    }
                    if (waitBadgeAlpha > 0.001f) {
                        // 淡黄底衬：让黄色文字在模糊层上依然可读
                        Box(
                            Modifier
                                .matchParentSize()
                                .padding(vertical = 14.dp)
                                .background(waitYellow.copy(alpha = 0.18f * waitBadgeAlpha))
                        )
                        Box(
                            Modifier.matchParentSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "已禁用",
                                fontSize = 15.sp,
                                color = waitYellow.copy(alpha = waitBadgeAlpha)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 开关卡片：自动认证 / 通知检测模式 / 增强模式，合到同一个框
        Card(Modifier.fillMaxWidth()) {
            // —— 自动认证 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自动认证",
                    fontSize = 15.sp,
                    color = onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                LiquidToggle(
                    selected = { autoAuth },
                    onSelect = onAutoAuthChange,
                    backdrop = canvasBackdrop,
                    onDragStateChange = handleControlDrag,
                )
            }
            PermDivider()
            // —— 通知检测模式 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    // 监听未连接时点按整行可请求重新绑定（原卡片行为）
                    .clickable(enabled = notifyAccessGranted && !listenerConnected) {
                        LogBuffer.add("UI", "ntfy rebind=request src=click")
                        GatewayNotificationListener.requestRebind(context)
                    }
                    .padding(horizontal = 16.dp)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "通知检测模式",
                    fontSize = 15.sp,
                    color = onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                LiquidToggle(
                    selected = { notifyDetect && notifyAccessGranted },
                    backdrop = canvasBackdrop,
                    onDragStateChange = handleControlDrag,
                    onSelect = { on ->
                        if (on) {
                            if (notifyAccessGranted) {
                                notifyDetect = true
                                PrefUtils.setNotifyDetectEnabled(context, true)
                                LogBuffer.add("UI", "detect=on")
                            } else {
                                // 无通知监听权限：跳转系统授权页（系统硬性限制，无法直接弹窗申请）
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (e: Exception) {
                                    LogBuffer.addDetail("NTFY", "open_settings err=" + e.message)
                                }
                            }
                        } else {
                            notifyDetect = false
                            PrefUtils.setNotifyDetectEnabled(context, false)
                            LogBuffer.add("UI", "detect=off")
                        }
                    }
                )
            }
            PermDivider()
            // —— 增强模式 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        hasRoot -> "增强模式（root）"
                        hasShizuku -> "增强模式（Shizuku）"
                        else -> "增强模式（需 root 或 Shizuku）"
                    },
                    fontSize = 15.sp,
                    color = onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                LiquidToggle(
                    selected = { enhancedMode },
                    backdrop = canvasBackdrop,
                    onDragStateChange = handleControlDrag,
                    onSelect = { on ->
                        if (on) {
                            enhancedMode = true
                            scope.launch(Dispatchers.IO) {
                                val rootOk = RootUtils.isRootAvailable()
                                val shizukuReady = ShizukuUtils.isReady()
                                withContext(Dispatchers.Main) {
                                    when {
                                        // 有 root -> 直接开启（优先 root）
                                        rootOk -> {
                                            hasRoot = true
                                            PrefUtils.setEnhancedModeEnabled(context, true)
                                            LogBuffer.add("UI", "enhanced=on via=root")
                                            // 增强模式开启：root 写 oom_score_adj + renice，把进程优先级拉满
                                            AutoLoginService.boostPriorityIfEnhanced(context)
                                        }
                                        // 有 Shizuku -> 用 Shizuku
                                        shizukuReady -> {
                                            hasShizuku = true
                                            PrefUtils.setEnhancedModeEnabled(context, true)
                                            ShizukuUtils.init(context)
                                            LogBuffer.add("UI", "enhanced=on via=shizuku")
                                            // 增强模式开启：尝试提升后台进程优先级（shell 权限下尽力而为）
                                            AutoLoginService.boostPriorityIfEnhanced(context)
                                        }
                                        // Shizuku 已连接但未授权 -> 弹出授权框
                                        ShizukuUtils.isShizukuAvailable() -> {
                                            try {
                                                Shizuku.requestPermission(shizukuReqCode)
                                            } catch (e: Exception) {
                                                enhancedMode = false
                                                PrefUtils.setEnhancedModeEnabled(context, false)
                                                LogBuffer.add("UI", "enhanced shizuku grant_err=" + e.message)
                                            }
                                        }
                                        // 都没有 -> 不开启
                                        else -> {
                                            enhancedMode = false
                                            PrefUtils.setEnhancedModeEnabled(context, false)
                                        }
                                    }
                                }
                            }
                        } else {
                            enhancedMode = false
                            PrefUtils.setEnhancedModeEnabled(context, false)
                            LogBuffer.add("UI", "enhanced=off")
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // 主题与配色入口（独立页面：主题模式 + 每类元素的调色板）
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(android.content.Intent(context, com.luminaauth.theme.ThemeSettingsActivity::class.java))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "主题与配色",
                    fontSize = 15.sp,
                    color = onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text("›", fontSize = 20.sp, color = secondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 高级设置入口
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(android.content.Intent(context, AdvancedSettingsActivity::class.java))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "高级设置",
                    fontSize = 15.sp,
                    color = onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text("›", fontSize = 20.sp, color = secondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 关于入口
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(android.content.Intent(context, AboutActivity::class.java))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("关于", fontSize = 15.sp, color = onSurface)
                    Text(realVersion, fontSize = 12.sp, color = secondary)
                }
                Text("›", fontSize = 20.sp, color = secondary)
            }
        }
        Spacer(Modifier.height(40.dp))
        }
      }

      // 固定在顶部的渐进模糊栏 + 大标题（HyperIsland 同款），采样本页自有 backdrop；
      // 滚动进行时降级为纯色栏
      TopBlurBar(
          backdrop = pageBackdrop,
          blurEnabled = effectiveBlur,
          modifier = Modifier
              .align(Alignment.TopCenter)
              .onSizeChanged { topBarHeightPx = it.height }
      ) {
          Column(
              Modifier
                  .statusBarsPadding()
                  .padding(horizontal = 20.dp)
                  .padding(top = 16.dp)
          ) {
              Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onSurface)
              Spacer(Modifier.height(16.dp))
          }
      }

    }
}

@Composable
private fun PermRow(label: String, desc: String, granted: Boolean, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val accent = MiuixTheme.colorScheme.primary
    val surfaceColor = MiuixTheme.colorScheme.surface
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    val green = LocalAppColors.current.success
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = onSurface)
            Text(desc, fontSize = 12.sp, color = secondary)
        }
        // 右侧小按钮：已开启 -> 绿色，未开启 -> 主题色
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (granted) green.copy(alpha = 0.15f) else accent.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (granted) "已开启" else "去开启",
                fontSize = 13.sp,
                color = if (granted) green else accent,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PermDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
    )
}

private fun openAutoStartSettings(context: Context) {
    // 小米/红米系统：应用自启动管理页
    try {
        context.startActivity(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
        )
        return
    } catch (e: Exception) {
    }
    // 通用：应用详情页
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)
            )
        )
    } catch (e: Exception) {
    }
}

// ==================== 权限状态检测 ====================
private fun isLocationGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun isNotificationGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

/** 检测 MIUI 自启动白名单（小米/红米），非 MIUI 或检测失败返回 false */
private fun isAutoStartGranted(context: Context): Boolean {
    try {
        val miui = context.getSystemService("miui")
        if (miui != null) {
            val m = miui.javaClass.getMethod("isAppOnWhiteList", String::class.java)
            return m.invoke(miui, context.packageName) as? Boolean ?: false
        }
    } catch (e: Exception) {}
    return false
}
