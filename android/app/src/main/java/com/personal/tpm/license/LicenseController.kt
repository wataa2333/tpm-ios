package com.personal.tpm.license

import java.time.YearMonth

sealed interface LicenseState {
    data object Checking : LicenseState
    /**
     * The app may be used. [notice] says something on the home screen, e.g. that the month has turned and the grace days
     * are running.
     */
    data class Allowed(val month: YearMonth?, val notice: String? = null) : LicenseState
    data class NeedKey(val reason: String) : LicenseState
}

sealed interface SubmitResult {
    data class Accepted(val month: YearMonth) : SubmitResult
    data class Rejected(val reason: String) : SubmitResult
}

/**
 * Decides whether the app may be used. No Android classes here: the store, the secret and the clock are handed in, so the
 * rules are unit tested on a PC. The clock is the phone's own (no network time: starting the app must not wait for anything).
 *
 * What is remembered is the month the person unlocked, not the typed key. A key works in its own month; for the
 * first [LicensePolicy.GRACE_DAYS] days of the next month it still works; next month's key may be entered early.
 */
class LicenseController(
    private val store: LicenseStore,
    private val secret: ByteArray?,
    private val clock: () -> Long,
) {
    private fun storedMonth(): YearMonth? = store.load().month?.let { try { YearMonth.parse(it) } catch (e: Exception) { null } }

    fun evaluate(): LicenseState {
        val now = clock()
        if (secret == null) return LicenseState.NeedKey("应用还没有配置密钥种子，请联系管理员重新打包。")
        val month = storedMonth() ?: return LicenseState.NeedKey("请输入本月密钥。")
        if (!LicensePolicy.isAllowed(month, now)) {
            return LicenseState.NeedKey("密钥已过期（${LicensePolicy.describe(month)}），请输入本月密钥。")
        }
        val notice = if (LicensePolicy.inGrace(month, now)) {
            "新的一个月已经开始：请在${LicensePolicy.GRACE_DAYS}号前输入本月密钥，之后将无法使用。"
        } else null
        return LicenseState.Allowed(month, notice = notice)
    }

    fun submit(raw: String): SubmitResult {
        val secret = secret ?: return SubmitResult.Rejected("应用还没有配置密钥种子，请联系管理员重新打包。")
        val now = clock()
        val typed = MonthKey.normalize(raw)
        if (typed.length != MonthKey.LENGTH) return SubmitResult.Rejected("密钥是 ${MonthKey.LENGTH} 位字母和数字")
        val current = LicensePolicy.currentMonth(now)
        // Last month, this month, next month: the typed key says which one it is.
        val matched = listOf(current, current.minusMonths(1), current.plusMonths(1)).firstOrNull { MonthKey.generate(secret, it) == typed }
            ?: return SubmitResult.Rejected("密钥不对，请核对后重新输入")
        if (!LicensePolicy.acceptsOnEntry(matched, now)) {
            return SubmitResult.Rejected("这是${LicensePolicy.describe(matched)}的密钥，已经过期")
        }
        // A later month is never replaced by an earlier one.
        val existing = storedMonth()
        val kept = if (existing != null && existing > matched) existing else matched
        store.save(StoredLicense(kept.toString()))
        return SubmitResult.Accepted(kept)
    }
}
