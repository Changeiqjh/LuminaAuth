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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import rikka.shizuku.Shizuku
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        @JvmField
        var foreground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PrefUtils.isOobeCompleted(this)) {
            startActivity(Intent(this, OobeActivity::class.java))
            finish()
            return
        }
        setContent {
            MiuixTheme {
                SchoolAutologinApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        super.onStop()
        foreground = false
    }
}

// ==================== 主界面 ====================
@Composable
fun SchoolAutologinApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 3 }
    val mainPagerState = rememberMainPagerState(pagerState)
    val surfaceColor = MiuixTheme.colorScheme.surface
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
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isp by remember { mutableStateOf("after") }
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
                username = saved[0]
                password = saved[1]
                isp = saved[2]
            }
        }
    }

    // 日志监听
    LaunchedEffect(logVersion) {
        LogBuffer.setListener { logVersion++ }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isControlDragging,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurSupported) Modifier.layerBackdrop(backdrop) else Modifier)
        ) { page ->
            when (page) {
                0 -> LoginPage(
                    username = username,
                    password = password,
                    isp = isp,
                    rememberPwd = rememberPwd,
                    autoAuth = autoAuth,
                    statusText = statusText,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onIspChange = {
                        isp = it
                        if (rememberPwd && username.isNotBlank() && password.isNotBlank()) {
                            PrefUtils.saveConfig(context, username, password, it)
                        }
                    },
                    onRememberChange = {
                        rememberPwd = it
                        PrefUtils.setRememberPwdEnabled(context, it)
                        if (it) {
                            if (username.isNotBlank() && password.isNotBlank()) {
                                PrefUtils.saveConfig(context, username, password, isp)
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
                        if (username.isBlank() || password.isBlank()) {
                            statusText = "请填写完整信息"
                        } else {
                            if (rememberPwd) {
                                PrefUtils.saveConfig(context, username, password, isp)
                            }
                            statusText = "正在登录..."
                            Thread {
                                try {
                                    val ip = LoginService.getLocalIPv4(context)
                                    if (ip == null) {
                                        statusText = "无法获取本机 IP，请连接 Wi-Fi"
                                    } else {
                                        val status = LoginService.checkNetworkStatus(ip)
                                        if ("online" == status) {
                                            statusText = "已在线，无需登录"
                                        } else if ("offline" == status) {
                                            val r = LoginService.doLogin(username, password, isp, ip)
                                            statusText = if (r.success) "登录成功" else "登录失败: " + r.message
                                        } else {
                                            statusText = "网络不可达"
                                        }
                                    }
                                } catch (e: Exception) {
                                    statusText = "异常: " + e.message
                                }
                            }.start()
                        }
                    },
                    onControlDrag = { isControlDragging = it }
                )
                1 -> LogPage(logVersion)
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
                    onControlDrag = { isControlDragging = it }
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
                    Icon(painter = painterResource(id = icon), contentDescription = label, modifier = Modifier.size(22.dp), tint = LocalContentColor.current)
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible
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
        LogBuffer.add("UI", "自动认证已开启")
    } else {
        context.stopService(intent)
        LogBuffer.add("UI", "自动认证已关闭")
    }
}

// ==================== 主页（登录） ====================
@Composable
private fun LoginPage(
    username: String,
    password: String,
    isp: String,
    rememberPwd: Boolean,
    autoAuth: Boolean,
    statusText: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onIspChange: (String) -> Unit,
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
        // 用户名（miuix 下划线输入框）
        TextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = "用户名",
        )
        Spacer(Modifier.height(8.dp))
        // 密码
        TextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = "密码",
        )
        Spacer(Modifier.height(16.dp))
        // ISP 选择（官方 LiquidBottomTabs 液态玻璃）
        LiquidBottomTabs(
            selectedTabIndex = { if (isp == "after") 0 else 1 },
            onTabSelected = { idx -> onIspChange(if (idx == 0) "after" else "after2") },
            backdrop = canvasBackdrop,
            tabsCount = 2,
            onDragStateChange = onControlDrag,
            modifier = Modifier.fillMaxWidth()
        ) {
            LiquidBottomTab(onClick = { onIspChange("after") }) {
                Text("教职工", fontSize = 14.sp, color = onSurface)
            }
            LiquidBottomTab(onClick = { onIspChange("after2") }) {
                Text("中国电信", fontSize = 14.sp, color = onSurface)
            }
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
        // 登录按钮（miuix 胶囊渐变按钮）
        Button(
            onClick = onLogin,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
private fun LogPage(logVersion: Int) {
    val context = LocalContext.current
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val boxBg = MiuixTheme.colorScheme.surfaceContainer
    // 读取日志（logVersion 变化时刷新）
    val logText = remember(logVersion) { LogBuffer.getAllText() }
    val scrollState = rememberScrollState()
    LaunchedEffect(logVersion) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("运行日志", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Spacer(Modifier.height(4.dp))
        Text("自动认证过程的实时记录", fontSize = 13.sp, color = secondary)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip("复制", Modifier.weight(1f)) {
                val txt = LogBuffer.getAllText()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("log", txt))
                LogBuffer.add("UI", "日志已复制")
            }
            ActionChip("清空", Modifier.weight(1f)) {
                LogBuffer.clear()
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(boxBg)
                .padding(12.dp)
        ) {
            Column(Modifier.verticalScroll(scrollState)) {
                Text(
                    if (logText.isBlank()) "暂无日志" else logText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = onSurface
                )
            }
        }
        Spacer(Modifier.height(40.dp))
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
) {
    val context = LocalContext.current
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val scope = rememberCoroutineScope()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    var enhancedMode by remember { mutableStateOf(PrefUtils.isEnhancedModeEnabled(context)) }
    val realVersion = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v" + pi.versionName + " · VersionCode " + pi.longVersionCode
        } catch (e: Exception) {
            "v1.0.2"
        }
    }
    var hasRoot by remember { mutableStateOf(false) }
    var hasShizuku by remember { mutableStateOf(false) }
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
    // Shizuku 授权结果回调
    val shizukuReqCode = 1001
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                enhancedMode = true
                PrefUtils.setEnhancedModeEnabled(context, true)
                ShizukuUtils.init(context)
                LogBuffer.add("UI", "增强模式已开启（Shizuku）")
                Toast.makeText(context, "增强模式已开启（Shizuku）", Toast.LENGTH_SHORT).show()
            } else {
                enhancedMode = false
                PrefUtils.setEnhancedModeEnabled(context, false)
                Toast.makeText(context, "未授予 Shizuku 权限，无法开启增强模式", Toast.LENGTH_LONG).show()
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Spacer(Modifier.height(4.dp))
        Text("权限、检测速度与开关", fontSize = 13.sp, color = secondary)
        Spacer(Modifier.height(16.dp))
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
                    onDragStateChange = onControlDrag,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // 自动认证卡片
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
        Spacer(Modifier.height(16.dp))
        // 增强模式卡片（需 root）
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            hasRoot -> "增强模式（root 提权）"
                            hasShizuku -> "增强模式（Shizuku 提权）"
                            else -> "增强模式（需 root 或 Shizuku）"
                        },
                        fontSize = 15.sp, color = onSurface
                    )
                    Text(
                        when {
                            hasRoot -> "用 root 权限检查校园网 WiFi，最可靠"
                            hasShizuku -> "用 Shizuku/shell 检查校园网 WiFi，更可靠"
                            else -> "需要 root 或 Shizuku 权限才能开启"
                        },
                        fontSize = 12.sp, color = secondary
                    )
                }
                LiquidToggle(
                    selected = { enhancedMode },
                    backdrop = canvasBackdrop,
                    onDragStateChange = onControlDrag,
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
                                            LogBuffer.add("UI", "增强模式已开启（root）")
                                            Toast.makeText(context, "增强模式已开启（root）", Toast.LENGTH_SHORT).show()
                                        }
                                        // 有 Shizuku -> 用 Shizuku
                                        shizukuReady -> {
                                            hasShizuku = true
                                            PrefUtils.setEnhancedModeEnabled(context, true)
                                            ShizukuUtils.init(context)
                                            LogBuffer.add("UI", "增强模式已开启（Shizuku）")
                                            Toast.makeText(context, "增强模式已开启（Shizuku）", Toast.LENGTH_SHORT).show()
                                        }
                                        // Shizuku 已连接但未授权 -> 弹出授权框
                                        ShizukuUtils.isShizukuAvailable() -> {
                                            try {
                                                Shizuku.requestPermission(shizukuReqCode)
                                            } catch (e: Exception) {
                                                enhancedMode = false
                                                PrefUtils.setEnhancedModeEnabled(context, false)
                                                Toast.makeText(context, "Shizuku 授权失败", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        // 都没有 -> 提示
                                        else -> {
                                            enhancedMode = false
                                            PrefUtils.setEnhancedModeEnabled(context, false)
                                            Toast.makeText(
                                                context,
                                                "需要 root 或 Shizuku 权限。root 可直接使用，Shizuku 需安装并授权本应用",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        } else {
                            enhancedMode = false
                            PrefUtils.setEnhancedModeEnabled(context, false)
                            LogBuffer.add("UI", "增强模式已关闭")
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun PermRow(label: String, desc: String, granted: Boolean, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val accent = MiuixTheme.colorScheme.primary
    val surfaceColor = MiuixTheme.colorScheme.surface
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
    val green = Color(0xFF43A047)
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
