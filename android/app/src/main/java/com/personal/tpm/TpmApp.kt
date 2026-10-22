package com.personal.tpm

import android.app.Application
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.DraftStore
import com.personal.tpm.data.PickerPrefs
import com.personal.tpm.data.Session
import com.personal.tpm.ui.theme.ThemeConfig

class TpmApp : Application() {
    lateinit var session: Session
        private set
    lateinit var vault: AccountVault
        private set
    lateinit var pickerPrefs: PickerPrefs
        private set
    lateinit var drafts: DraftStore
        private set

    override fun onCreate() {
        super.onCreate()
        ThemeConfig.load(this)
        com.personal.tpm.ui.AppPreferences.load(this)
        com.personal.tpm.ui.CardPreferences.load(this)
        com.personal.tpm.ui.Motion.load(this)
        session = Session(this)
        vault = AccountVault(this)
        pickerPrefs = PickerPrefs(this)
        drafts = DraftStore(this)
        Thread { drafts.warmUp() }.start()
    }
}
