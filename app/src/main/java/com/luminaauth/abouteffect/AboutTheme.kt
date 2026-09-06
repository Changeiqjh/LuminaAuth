package com.luminaauth.abouteffect

import android.app.UiModeManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalEnableBlur = compositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean {
    val context = LocalContext.current
    val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
