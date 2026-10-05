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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import com.luminaauth.plugin.PluginLoader
import com.luminaauth.plugin.PluginManager
import com.luminaauth.plugin.PluginParseException
import com.luminaauth.plugin.cleanHiddenChars
import com.luminaauth.plugin.PluginRuntime
import com.luminaauth.plugin.PluginSource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import com.luminaauth.theme.LuminaAuthTheme
import com.luminaauth.theme.LocalAppColors

class AdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaAuthTheme {
                AdvancedSettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

private fun getEffectiveAuthUrl(customInputUrl: String): String {
    return PluginRuntime.getEffectiveLoginUrl(AppContext.get(), customInputUrl)
}

@Composable
private fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val appColors = LocalAppColors.current
    val lazyListState = rememberLazyListState()
    val ssidState = rememberTextFieldState()
    var customAuthUrl by rememberSaveable { mutableStateOf("") }
    var ssidList by remember { mutableStateOf(PrefUtils.getSsidList(AppContext.get()).split(",").filter { it.isNotBlank() }) }
    var showResetSsidDialog by remember { mutableStateOf(false) }
    var showResetUrlDialog by remember { mutableStateOf(false) }
    // 插件导入/解析失败的详细原因：用底部对话框完整展示，Toast 装不下多行报错
    var pluginImportError by remember { mutableStateOf<String?>(null) }
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
                            textStyle = TextStyle(color = colorScheme.onSurface, fontSize = 16.sp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = "添加",
                                color = colorScheme.primary,
                                onClick = {
                                    val ssid = ssidState.text.toString().trim()
                                    if (ssid.isNotEmpty() && !ssidList.contains(ssid)) {
                                        PrefUtils.addSsid(AppContext.get(), ssid)
                                        ssidState.edit { replace(0, length, "") }
                                        refreshList()
                                        // SSID 列表变更：重新评估当前网络（下次轮询按新列表判断）
                                        AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "恢复默认",
                                color = appColors.danger,
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
                // 分割线：隔开 SSID 列表与登录认证地址
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                // 登录地址卡片（标题在卡片内）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "登录认证地址",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "默认地址由认证插件提供",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceContainerVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        // 原生 miuix TextField
                        TextField(
                            value = customAuthUrl,
                            onValueChange = { customAuthUrl = it },
                            label = "认证地址（留空使用插件默认地址）",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = colorScheme.onSurface, fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = "保存",
                                color = colorScheme.primary,
                                onClick = {
                                    val url = customAuthUrl.trim()
                                    if (url.isEmpty()) {
                                        PrefUtils.resetLoginUrl(AppContext.get())
                                    } else {
                                        PrefUtils.setLoginUrl(AppContext.get(), url)
                                    }
                                    LogBuffer.addDetail("PLUGIN", "auth_url eff=${getEffectiveAuthUrl(customAuthUrl)}")
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                text = "恢复默认",
                                color = appColors.danger,
                                onClick = { customAuthUrl = "" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                PluginManagerCard(onError = { pluginImportError = it })
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
                            color = appColors.danger,
                            onClick = {
                                PrefUtils.resetSsidList(AppContext.get())
                                refreshList()
                                // SSID 列表变更：重新评估当前网络
                                AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                                showResetSsidDialog = false
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
                            color = appColors.danger,
                            onClick = {
                                PrefUtils.resetLoginUrl(AppContext.get())
                                customAuthUrl = ""
                                showResetUrlDialog = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // 插件解析失败详情对话框（底部弹出）
    AnimatedVisibility(
        visible = pluginImportError != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { pluginImportError = null },
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
                    Text("插件导入失败", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = appColors.danger)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        pluginImportError ?: "",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceContainerVariant,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    ActionButton(
                        text = "关闭",
                        color = appColors.danger,
                        onClick = { pluginImportError = null },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Plugin management card: list/select active plugin, import a new
 * *.plugin.yaml document, delete external plugins.
 */
@Composable
private fun PluginManagerCard(onError: (String) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val plugins = remember(tick) {
        runCatching { PluginManager.list(AppContext.get()) }.getOrDefault(emptyList())
    }
    val activeId = remember(tick) {
        runCatching { PluginManager.active(AppContext.get()).id }.getOrNull()
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val inputStream = AppContext.get().contentResolver.openInputStream(uri)
            val rawYaml = inputStream?.bufferedReader().use { it?.readText() }
            inputStream?.close()
            if (rawYaml.isNullOrBlank()) {
                onError("文件读取失败：无法从所选文件读到任何内容")
                return@rememberLauncherForActivityResult
            }
            // 与 PluginLoader 内部使用完全相同的清洗，保证落盘副本 == 通过校验的文本
            val yamlText = cleanHiddenChars(rawYaml)
            val plugin = PluginLoader.load(yamlText, "imported.plugin.yaml", PluginSource.EXTERNAL)
            val pluginDir = PluginManager.pluginDir(AppContext.get())
            if (!pluginDir.exists()) pluginDir.mkdirs()
            val outFile = File(pluginDir, "${plugin.id}.plugin.yaml")
            outFile.writeText(yamlText)
            PluginManager.reload(AppContext.get())
            Toast.makeText(AppContext.get(), "插件导入成功", Toast.LENGTH_SHORT).show()
            tick++
        } catch (e: PluginParseException) {
            // 精准报错：条目标号 / 未知键 / 端口范围等细节原样展示，不再统一提示
            onError("插件解析失败：${e.message}")
        } catch (e: Exception) {
            e.printStackTrace()
            onError("插件解析失败：${e.message}\n（已自动清理隐藏字符，若仍报错，请检查缩进）")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(colorScheme.surfaceContainer, Color.Transparent)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("认证插件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("选择或导入 *.plugin.yaml 认证插件", fontSize = 12.sp, color = colorScheme.onSurfaceContainerVariant)
            Spacer(Modifier.height(12.dp))

            plugins.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            PluginManager.setActive(AppContext.get(), p.id)
                            tick++
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, color = colorScheme.onSurface)
                        val tag = if (p.source == PluginSource.BUILTIN) "内置" else "外部"
                        Text("$tag · v${p.version}", fontSize = 11.sp, color = colorScheme.onSurfaceContainerVariant)
                    }
                    if (p.id == activeId) {
                        Text("使用中", fontSize = 12.sp, color = colorScheme.primary)
                    } else if (p.source == PluginSource.EXTERNAL) {
                        Icon(
                            MiuixIcons.Close,
                            contentDescription = "删除",
                            tint = colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    PluginManager.delete(AppContext.get(), p)
                                    tick++
                                }
                                .padding(4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.primary.copy(alpha = 0.12f))
                    .clickable {
                        importLauncher.launch(
                            arrayOf("text/plain", "text/yaml", "application/x-yaml", "text/*", "*/*")
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导入插件",
                    color = colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
