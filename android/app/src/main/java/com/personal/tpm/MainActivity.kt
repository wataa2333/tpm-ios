package com.personal.tpm

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.personal.tpm.ui.AppRoot
import com.personal.tpm.ui.theme.AppTheme
import com.personal.tpm.ui.theme.ThemeConfig
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap

class MainActivity : FragmentActivity() {
    private var launchIconBounds by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var launchIconSnapshot by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var launchSystemReleased by androidx.compose.runtime.mutableStateOf(Build.VERSION.SDK_INT < 31)
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) {
            window.navigationBarColor = Color.TRANSPARENT
            window.navigationBarDividerColor = Color.TRANSPARENT
            if(Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    /**
     * The interface size (the owner's step times the fit to the screen width) is applied here, to the density of the Activity's own resources,
     * not by a Compose override: sheets, dialogs and pickers are windows of their own, and all of them take their size from the Activity, so
     * they are all drawn at the same size on any phone and any Compose version. Changing the step recreates the Activity once.
     */
    private var rawDensityDpi = 0
    private var rawFontScale = 0f

    override fun attachBaseContext(base: Context) {
        rawDensityDpi = base.resources.configuration.densityDpi
        rawFontScale = base.resources.configuration.fontScale
        val step = base.getSharedPreferences("tpm_theme", Context.MODE_PRIVATE).getInt("size5", 1)
        val config = Configuration(base.resources.configuration)
        val scale = ThemeConfig.fitToWidth(config.smallestScreenWidthDp) * ThemeConfig.scaleFactor(step)
        config.densityDpi = (config.densityDpi * scale).roundToInt()
        config.fontScale = config.fontScale.coerceIn(0.9f, 1.15f)
        // The sizes in dp grow as the density falls back: keep them in line, as the system does when the density changes.
        config.screenWidthDp = (config.screenWidthDp / scale).toInt()
        config.screenHeightDp = (config.screenHeightDp / scale).toInt()
        config.smallestScreenWidthDp = (config.smallestScreenWidthDp / scale).toInt()
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** The phone's own display size or font size changed (the Activity handles that itself): start again so the interface size is worked out anew. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.densityDpi != rawDensityDpi || newConfig.fontScale != rawFontScale) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash ->
            splash.iconView?.let { icon ->
                val location = IntArray(2)
                icon.getLocationInWindow(location)
                if (icon.width > 0 && icon.height > 0) {
                    runCatching {
                        val bitmap = android.graphics.Bitmap.createBitmap(icon.width, icon.height, android.graphics.Bitmap.Config.ARGB_8888)
                        icon.draw(android.graphics.Canvas(bitmap))
                        bitmap.asImageBitmap()
                    }.getOrNull()?.let { snapshot ->
                        launchIconSnapshot = snapshot
                        launchIconBounds = androidx.compose.ui.geometry.Rect(location[0].toFloat(), location[1].toFloat(),
                            (location[0] + icon.width).toFloat(), (location[1] + icon.height).toFloat())
                    }
                }
            }
            launchSystemReleased = true
            // Keep the system frame until Compose has drawn the matching snapshot once.
            val observer = window.decorView.viewTreeObserver
            val listener = object : android.view.ViewTreeObserver.OnDrawListener {
                private var scheduled = false
                override fun onDraw() {
                    if (scheduled) return
                    scheduled = true
                    window.decorView.post {
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                        splash.remove()
                    }
                }
            }
            observer.addOnDrawListener(listener)
            window.decorView.invalidate()
        }
        // Both system bars are transparent: the app draws behind the status bar and behind the navigation bar / gesture line, and the
        // navigation buttons get no grey scrim of their own (on phones that still draw one, the contrast enforcement is switched off).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        applyRefreshRate()
        val session = (application as TpmApp).session
        setContent {
            AppTheme {
                com.personal.tpm.ui.CharacterLaunch(systemReleased = launchSystemReleased, systemIconBounds = launchIconBounds,
                    systemIconSnapshot = launchIconSnapshot, onFinished = { launchIconSnapshot = null; launchIconBounds = null }) { finished ->
                    AppRoot(session, (application as TpmApp).vault, (application as TpmApp).pickerPrefs, (application as TpmApp).drafts, launchFinished = finished)
                }
            }
        }
    }

    /**
     * Asks for the screen's highest refresh rate (90/120 Hz) for this window. Phones keep many apps at 60 Hz on purpose ("smart" rate
     * lists, battery saving); a window that asks for the rate is let through on most of them, but the phone has the last word.
     */
    /** Asks for the screen's highest refresh rate (or, with the switch on the developer page off, leaves the choice to the system). */
    fun applyRefreshRate() {
        @Suppress("DEPRECATION")
        val screen = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        com.personal.tpm.ui.applyWindowRefreshRate(window, screen)
    }
}
