package com.personal.tpm.ui

/** Keep unsupported RenderEffect/RuntimeShader paths out of older devices entirely. */
enum class GlassRendering { Solid, Translucent, Blur, Liquid }

fun glassRendering(enabled: Boolean, sdk: Int, hardwareAccelerated: Boolean): GlassRendering = when {
    !enabled -> GlassRendering.Solid
    !hardwareAccelerated || sdk < 31 -> GlassRendering.Translucent
    sdk < 33 -> GlassRendering.Blur
    else -> GlassRendering.Liquid
}
