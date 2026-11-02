package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassEffectSettings() {
    val effects = AppPreferences.glassEffects
    val rendering = currentGlassRendering()
    val supportsBlur = rendering == GlassRendering.Blur || rendering == GlassRendering.Liquid
    val supportsLens = rendering == GlassRendering.Liquid
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模糊档位", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassBlur.entries.forEach { level ->
                GlassFilterChip(effects.blur == level, { AppPreferences.updateGlassEffects(effects.copy(blur = level)) },
                    label = { Text(level.label) }, enabled = supportsBlur, modifier = Modifier.weight(1f))
            }
        }
        Text("立即生效，重启后保留。默认低模糊，色散和阴影关闭；关闭模糊仍可保留高光和折射。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassEffectToggle("边缘高光", "细边缘与触摸光泽", effects.highlight, supportsBlur) {
            AppPreferences.updateGlassEffects(effects.copy(highlight = it))
        }
        GlassEffectToggle("镜头折射", "玻璃边缘的弯曲与厚度", effects.refraction, supportsLens) {
            AppPreferences.updateGlassEffects(effects.copy(refraction = it))
        }
        GlassEffectToggle("边缘色散", "折射边缘的轻微彩色分离", effects.dispersion, supportsLens && effects.refraction) {
            AppPreferences.updateGlassEffects(effects.copy(dispersion = it))
        }
        GlassEffectToggle("柔和阴影", "增加按钮的悬浮层次", effects.shadow, supportsBlur) {
            AppPreferences.updateGlassEffects(effects.copy(shadow = it))
        }
        GlassEffectToggle("弹性动效", "按下形变、拖动跟随与松手回弹", effects.motion) {
            AppPreferences.updateGlassEffects(effects.copy(motion = it))
        }
        if (!supportsLens) Text(if (supportsBlur) "当前设备支持模糊；折射与色散需要 Android 13 或更高版本。"
            else "当前设备使用半透明材质；模糊、高光和折射在支持的设备上生效。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassTextButton(onClick = { AppPreferences.updateGlassEffects(GlassEffects()) }) { Text("恢复玻璃效果默认值") }
    }
}

@Composable
private fun GlassEffectToggle(title: String, description: String, checked: Boolean, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassSwitch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
