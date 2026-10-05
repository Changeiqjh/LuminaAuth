package com.luminaauth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.luminaauth.abouteffect.LocalEnableBlur
import com.luminaauth.about.AboutScreenMiuix
import com.luminaauth.about.AboutScreenActions
import com.luminaauth.about.AboutUiState
import com.luminaauth.about.LinkInfo
import com.luminaauth.theme.LuminaAuthTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaAuthTheme {
                CompositionLocalProvider(LocalEnableBlur provides true) {
                    val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                    val realVersionCode = pkgInfo.longVersionCode
                    val realVersionName = pkgInfo.versionName
                    val state = AboutUiState(
                        title = "关于",
                        appName = "LuminaAuth",
                        versionName = "v$realVersionName · VersionCode $realVersionCode",
                        links = listOf(
                            LinkInfo("版本: v" + realVersionName + " (VersionCode: " + realVersionCode + ")", ""),
                            LinkInfo("最低系统: Android 10 (API 29)", ""),
                            LinkInfo("编译 SDK: Android 17 (API 37)", ""),
                            LinkInfo("技术栈: Compose + miuix-kmp", ""),
                            LinkInfo("提权方式: Root / Shizuku", ""),
                            LinkInfo("开源协议: GPL-3.0-or-later", ""),
                            LinkInfo("UI组件: AndroidLiquidGlass (Apache-2.0, Kyant0)", ""),
                            LinkInfo("UI框架: miuix-kmp (GPL-3.0, Yukonga)", ""),
                            LinkInfo("提权库: Shizuku (MIT, Rikka)", ""),
                        )
                    )
                    val actions = AboutScreenActions(
                        onBack = { finish() },
                        onOpenLink = { }
                    )
                    AboutScreenMiuix(state = state, actions = actions)
                }
            }
        }
    }
}
