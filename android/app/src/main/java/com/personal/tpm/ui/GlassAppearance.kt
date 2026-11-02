package com.personal.tpm.ui

/** Source resolution only; foreground text and the optical effect geometry stay at full size. */
enum class GlassSampling(val label: String, val divisor: Int) {
    Saver("省电", 3), Balanced("均衡", 2), Clear("清晰", 1);

    fun pixels(length: Int) = ((length.coerceAtLeast(1).toLong() + divisor - 1) / divisor).toInt()

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: Saver
    }
}

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
    val shadow: Boolean = true,
    val motion: Boolean = true,
    val sampling: GlassSampling = GlassSampling.Saver,
)
