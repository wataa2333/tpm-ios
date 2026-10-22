package com.personal.tpm.ui

import android.view.Display
import android.view.Window
import com.personal.tpm.ui.theme.ThemeConfig

/** A sheet has its own Window; the Activity window's request does not configure it. */
fun applyWindowRefreshRate(window: Window, display: Display?, enabled: Boolean = ThemeConfig.highRefresh) {
    val mode = if (enabled) {
        val screen = display ?: return
        val current = screen.mode
        screen.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: return
    } else 0
    val params = window.attributes
    // Avoid relayout on every sheet recomposition when its request has not changed.
    if (params.preferredDisplayModeId != mode) {
        params.preferredDisplayModeId = mode
        window.attributes = params
    }
}
