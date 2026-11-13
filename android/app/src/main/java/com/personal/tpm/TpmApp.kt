package com.personal.tpm

import android.app.Application
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.DraftStore
import com.personal.tpm.data.PickerPrefs
import com.personal.tpm.data.Session
import com.personal.tpm.ui.theme.ThemeConfig

class TpmApp : Application() {
    val session by lazy { Session(this) }
    val vault by lazy { AccountVault(this) }
    val pickerPrefs by lazy { PickerPrefs(this) }
    val drafts by lazy { DraftStore(this) }

    override fun onCreate() {
        super.onCreate()
        ThemeConfig.load(this)
        com.personal.tpm.ui.AppPreferences.load(this)
        com.personal.tpm.ui.Motion.load(this)
    }
}
