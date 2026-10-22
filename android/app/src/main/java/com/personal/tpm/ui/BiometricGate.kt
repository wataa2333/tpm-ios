package com.personal.tpm.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun Context.findActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Face / fingerprint (or the screen lock as the fallback) in front of the remembered accounts: a remembered account signs in
 * without typing a password, so the phone first confirms it is the owner. If no screen lock is set, protected remembered sign-in is refused.
 */
object BiometricGate {
    private const val KEY = "protectSavedAccounts"
    sealed interface Outcome {
        data object Passed : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** On unless the owner turned it off. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY, value).apply()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("tpm_prefs", Context.MODE_PRIVATE)

    // BIOMETRIC_STRONG together with DEVICE_CREDENTIAL is not allowed on Android 10, so the weak class is used.
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** [force] asks even when the protection is switched off (turning the protection off is itself checked). */
    suspend fun authenticate(activity: FragmentActivity, reason: String, force: Boolean = false): Outcome {
        if (!force && !isEnabled(activity)) return Outcome.Passed
        val secure = activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure
        if (!secure) return Outcome.Failed("请先在手机系统设置中设置锁屏密码，再使用已记住的账号")
        if (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS)
            return (activity as? com.personal.tpm.MainActivity)?.confirmScreenLock(reason) ?: Outcome.Failed("当前页面无法打开身份验证")
        val result = authenticatePrompt(activity, reason)
        return if (result is Outcome.Failed) (activity as? com.personal.tpm.MainActivity)?.confirmScreenLock(reason) ?: result else result
    }

    private suspend fun authenticatePrompt(activity: FragmentActivity, reason: String): Outcome {
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(Outcome.Passed)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)
                    if (continuation.isActive) continuation.resume(if (cancelled) Outcome.Cancelled else Outcome.Failed("没有通过验证：$errString"))
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder().setTitle("验证身份").setSubtitle(reason).setAllowedAuthenticators(AUTHENTICATORS).build()
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
