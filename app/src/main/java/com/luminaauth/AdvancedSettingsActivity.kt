package com.luminaauth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

class AdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeController = remember {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.MonetSystem,
                    keyColor = Color(0xFF3482FF),
                    isDark = ThemeUtils.getIsDark(this)
                )
            }
            MiuixTheme(controller = themeController) {
                AdvancedSettingsScreen(
                    onBack = { finish() },
                    onToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val lazyListState = rememberLazyListState()
    val ssidState = rememberTextFieldState()
    val urlState = rememberTextFieldState()
    var ssidList by remember { mutableStateOf(PrefUtils.getSsidList(AppContext.get()).split(",").filter { it.isNotBlank() }) }
    var showResetSsidDialog by remember { mutableStateOf(false) }
    var showResetUrlDialog by remember { mutableStateOf(false) }
    val deletingSsids = remember { mutableStateListOf<String>() }

    val refreshList = {
        ssidList = PrefUtils.getSsidList(AppContext.get()).split(",").filter { it.isNotBlank() }
    }

    // 通用按钮样式
    @Composable
    fun ActionButton(
        text: String,
        color: Color,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.12f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "高级设置",
                color = colorScheme.surface,
                titleColor = colorScheme.onSurface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回", tint = colorScheme.onSurface)
                    }
                },
            )
        },
        popupHost = { },
        containerColor = colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            item {
                // 标题卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(MiuixIcons.Link, contentDescription = "WiFi", tint = colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("目标 WiFi SSID 列表", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                            Text("连接到以下 WiFi 时自动认证", fontSize = 12.sp, color = colorScheme.onSurfaceContainerVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 添加 SSID 卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("添加新 SSID", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        // 原生 miuix TextField
                        TextField(
                            state = ssidState,
                            modifier = Modifier.fillMaxWidth(),
                            label = "WiFi 名称",
                            textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = "添加",
                                color = colorScheme.primary,
                                onClick = {
                                    val ssid = ssidState.text.toString().trim()
                                    if (ssid.isEmpty()) {
                                        onToast("请输入 WiFi 名称")
                                    } else if (ssidList.contains(ssid)) {
                                        onToast("该 SSID 已存在")
                                    } else {
                                        PrefUtils.addSsid(AppContext.get(), ssid)
                                        ssidState.edit { replace(0, length, "") }
                                        refreshList()
                                        // SSID 列表变更：重新评估当前网络（下次轮询按新列表判断）
                                        AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                                        onToast("已添加: $ssid")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "恢复默认",
                                color = Color(0xFFFF4444),
                                onClick = { showResetSsidDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 列表标题
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前列表 (${ssidList.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text("点击删除", fontSize = 12.sp, color = colorScheme.onSurfaceContainerVariant)
                }
            }

            // SSID 列表项
            items(ssidList, key = { it }) { ssid ->
                AnimatedVisibility(
                    visible = ssid !in deletingSsids,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (ssid !in deletingSsids) {
                                        deletingSsids.add(ssid)
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            PrefUtils.removeSsid(AppContext.get(), ssid)
                                            refreshList()
                                            // SSID 列表变更：重新评估当前网络
                                            AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                                            deletingSsids.remove(ssid)
                                        }, 300)
                                        onToast("已删除: $ssid")
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(MiuixIcons.Link, contentDescription = "SSID", tint = colorScheme.onSurfaceContainerVariant, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(ssid, fontSize = 15.sp, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Icon(MiuixIcons.Close, contentDescription = "删除", tint = colorScheme.onSurfaceContainerVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                // 登录地址配置标题
                Text("登录认证地址", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                // 登录地址卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        // 原生 miuix TextField
                        TextField(
                            state = urlState,
                            modifier = Modifier.fillMaxWidth(),
                            label = "认证地址（留空使用默认地址）",
                            textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = "保存",
                                color = colorScheme.primary,
                                onClick = {
                                    val url = urlState.text.toString().trim()
                                    if (url.isEmpty()) {
                                        PrefUtils.resetLoginUrl(AppContext.get())
                                        onToast("已使用默认地址")
                                    } else {
                                        PrefUtils.setLoginUrl(AppContext.get(), url)
                                        onToast("登录地址已保存")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "恢复默认",
                                color = Color(0xFFFF4444),
                                onClick = { showResetUrlDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "提示：默认使用 https 协议。部分校园网不支持 https，如认证失败可尝试改为 http。",
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceContainerVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "提示：SSID 区分大小写，点击列表项可删除",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceContainerVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    // 恢复 SSID 确认对话框（底部弹出）
    AnimatedVisibility(
        visible = showResetSsidDialog,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { showResetSsidDialog = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateEnterExit(
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ),
                colors = CardDefaults.defaultColors(colorScheme.surface, Color.Transparent)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("确认恢复", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Text("确定要恢复默认 SSID 列表吗？当前自定义的 SSID 将被清除。", fontSize = 14.sp, color = colorScheme.onSurfaceContainerVariant, lineHeight = 20.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton(
                            text = "取消",
                            color = colorScheme.onSurface,
                            onClick = { showResetSsidDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = "确定",
                            color = Color(0xFFFF4444),
                            onClick = {
                                PrefUtils.resetSsidList(AppContext.get())
                                refreshList()
                                // SSID 列表变更：重新评估当前网络
                                AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                                showResetSsidDialog = false
                                onToast("已恢复默认列表")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // 恢复登录地址确认对话框（底部弹出）
    AnimatedVisibility(
        visible = showResetUrlDialog,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { showResetUrlDialog = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateEnterExit(
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ),
                colors = CardDefaults.defaultColors(colorScheme.surface, Color.Transparent)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("确认恢复", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Text("确定要恢复默认登录地址吗？当前自定义的地址将被清除。", fontSize = 14.sp, color = colorScheme.onSurfaceContainerVariant, lineHeight = 20.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton(
                            text = "取消",
                            color = colorScheme.onSurface,
                            onClick = { showResetUrlDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = "确定",
                            color = Color(0xFFFF4444),
                            onClick = {
                                PrefUtils.resetLoginUrl(AppContext.get())
                                urlState.edit { replace(0, length, "") }
                                showResetUrlDialog = false
                                onToast("已恢复默认地址")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
