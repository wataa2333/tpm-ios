package com.personal.tpm.ui

import androidx.fragment.app.FragmentActivity
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.SavedAccount
import com.personal.tpm.data.Session
import kotlinx.coroutines.CancellationException

sealed interface RememberedSignIn {
    data object Done : RememberedSignIn
    data object Cancelled : RememberedSignIn
    data class Failed(val message: String) : RememberedSignIn
}

/** A remembered account signs in with its saved password, after the phone has confirmed it is the owner. */
suspend fun signInRemembered(activity: FragmentActivity, session: Session, vault: AccountVault, account: SavedAccount): RememberedSignIn {
    when (val outcome = BiometricGate.authenticate(activity, "使用已记住的账号 " + account.displayName.ifEmpty { account.username })) {
        BiometricGate.Outcome.Passed -> {}
        BiometricGate.Outcome.Cancelled -> return RememberedSignIn.Cancelled
        is BiometricGate.Outcome.Failed -> return RememberedSignIn.Failed(outcome.message)
    }
    val password = vault.password(account.username) ?: return RememberedSignIn.Failed("没有找到这个账号保存的密码，请重新登录并打开“记住密码”")
    return try {
        session.login(account.username, password)
        vault.save(account.username, password, session.fullName.ifEmpty { account.username })
        RememberedSignIn.Done
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { RememberedSignIn.Failed(e.message ?: "登录失败") }
}
