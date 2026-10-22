package com.personal.tpm.license

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The licence state. It is worked out at once from the phone's own clock and the saved month: nothing waits for the network, so the
 * app opens straight into the right screen.
 */
class LicenseViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = LicenseController(
        store = KeystoreLicenseStore(app),
        secret = LicenseSecret.bytes,
        clock = System::currentTimeMillis,
    )
    private val _state = MutableStateFlow(controller.evaluate())
    val state: StateFlow<LicenseState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    /** The answer to the last key the person entered, when it was refused. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Back in the foreground: the month may have turned. */
    fun recheck() { _state.value = controller.evaluate() }

    fun clearMessage() { _message.value = null }

    fun submit(raw: String) {
        when (val result = controller.submit(raw)) {
            is SubmitResult.Rejected -> _message.value = result.reason
            is SubmitResult.Accepted -> { _message.value = null; recheck() }
        }
    }
}
