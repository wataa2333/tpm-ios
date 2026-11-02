package com.personal.tpm.ui

enum class GlassBlur(val label: String, val radiusDp: Float) {
    Off("关闭", 0f), Low("低", 2f), Medium("中", 6f), High("高", 12f);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: Low
    }
}

data class GlassEffects(
    val blur: GlassBlur = GlassBlur.Low,
    val highlight: Boolean = true,
    val refraction: Boolean = true,
    val dispersion: Boolean = false,
    val shadow: Boolean = false,
    val motion: Boolean = true,
)
