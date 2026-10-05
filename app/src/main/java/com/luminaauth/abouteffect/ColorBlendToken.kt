// Mirrored from compose-miuix-ui example.

package com.luminaauth.abouteffect

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

object ColorBlendToken {

    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )

    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )

    // Logo 模糊混色（深色主题）
    val LogoBlend_Dark = listOf(
        BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
        BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
    )

    // Logo 模糊混色（浅色主题）
    val LogoBlend_Light = listOf(
        BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
        BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
    )
}
