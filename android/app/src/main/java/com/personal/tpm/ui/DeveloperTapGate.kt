package com.personal.tpm.ui

/** One trigger per visit. Further taps stay blocked until the title's page resumes. */
class DeveloperTapGate {
    private var count = 0
    private var previous: Long? = null
    private var triggered = false
    fun tap(now: Long): Boolean {
        if (triggered) return false
        val last = previous
        count = if (last == null || now < last || now - last > 1500L) 1 else count + 1
        previous = now
        if (count < 10) return false
        triggered = true
        return true
    }
    fun reset() { count = 0; previous = null; triggered = false }
}
