package com.personal.tpm

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.personal.tpm.license.LicenseViewModel
import com.personal.tpm.ui.AppRoot
import com.personal.tpm.ui.theme.AppTheme
import com.personal.tpm.ui.theme.ThemeConfig
import kotlin.math.roundToInt

class MainActivity : FragmentActivity() {
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) {
            window.navigationBarColor = Color.TRANSPARENT
            window.navigationBarDividerColor = Color.TRANSPARENT
            if(Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    private var credentialResult: kotlinx.coroutines.CancellableContinuation<com.personal.tpm.ui.BiometricGate.Outcome>? = null
    @Suppress("DEPRECATION")
    suspend fun confirmScreenLock(reason: String): com.personal.tpm.ui.BiometricGate.Outcome {
        val manager = getSystemService(android.app.KeyguardManager::class.java)
        val intent = manager.createConfirmDeviceCredentialIntent("验证身份", reason)
            ?: return com.personal.tpm.ui.BiometricGate.Outcome.Failed("请先在系统设置中设置锁屏密码")
        if (credentialResult?.isActive == true) return com.personal.tpm.ui.BiometricGate.Outcome.Failed("已有身份验证正在进行")
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            credentialResult = continuation
            continuation.invokeOnCancellation { if (credentialResult === continuation) credentialResult = null }
            try { startActivityForResult(intent, 7441) }
            catch (_: Exception) {
                credentialResult = null
                continuation.resumeWith(Result.success(com.personal.tpm.ui.BiometricGate.Outcome.Failed("无法打开系统锁屏验证")))
            }
        }
    }
    @Deprecated("System credential compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7441) {
            val pending = credentialResult; credentialResult = null
            if (pending?.isActive == true) pending.resumeWith(Result.success(if (resultCode == RESULT_OK) com.personal.tpm.ui.BiometricGate.Outcome.Passed else com.personal.tpm.ui.BiometricGate.Outcome.Cancelled))
        }
    }
    private val license: LicenseViewModel by viewModels()

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
            // Back in the foreground after a while: the month may have turned.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { license.recheck() }
            AppTheme {
                AppRoot(license, session, (application as TpmApp).vault, (application as TpmApp).pickerPrefs, (application as TpmApp).drafts)
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
