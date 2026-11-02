package com.personal.tpm.ui

import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

fun glassSupportDescription(mode: GlassRendering, enabled: Boolean): String {
    val capability = when(mode) {
        GlassRendering.Liquid -> "本机支持背景模糊与镜头折射。"
        GlassRendering.Blur -> "本机支持背景模糊，镜头折射需要 Android 13 或更高版本。"
        else -> "本机当前使用半透明兼容效果，无法启用背景模糊和镜头折射。"
    }
    return capability + if (enabled) "效果已开启，可在开发者选项中调整。" else "效果当前已关闭，已保留您的选择。"
}

@Composable
fun GlassIntroduction() {
    if (!AppPreferences.showGlassIntroduction) return
    val capability = glassRendering(true, Build.VERSION.SDK_INT, LocalView.current.isHardwareAccelerated)
    GlassAlertDialog(onDismissRequest = AppPreferences::dismissGlassIntroduction,
        title = { Text("本版本已加入液态玻璃效果") },
        text = { Text("Android 13 及以上支持模糊与折射，Android 12 支持模糊，较旧系统使用半透明兼容效果。\n\n" +
            "您当前的系统：Android ${Build.VERSION.RELEASE}。\n" + glassSupportDescription(capability, AppPreferences.liquidGlass)) },
        confirmButton = { GlassTextButton(onClick = AppPreferences::dismissGlassIntroduction) { Text("知道了") } })
}
