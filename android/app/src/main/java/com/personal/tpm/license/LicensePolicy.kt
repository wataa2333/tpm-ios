package com.personal.tpm.license

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * When a key may be used. Pure rules, no clock of their own: the caller passes the trusted time.
 *
 * - A key for month M works throughout M (China time).
 * - For the first [GRACE_DAYS] days of the next month it still works, so nobody is locked out on the 1st.
 * - A key for the coming month may be entered in advance (and then simply works from the 1st on).
 */
object LicensePolicy {
    const val GRACE_DAYS = 3
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun currentMonth(nowMillis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone))
    private fun dayOfMonth(nowMillis: Long): Int = Instant.ofEpochMilli(nowMillis).atZone(zone).dayOfMonth

    /** Whether a key of this month still unlocks the app at that moment. */
    fun isAllowed(keyMonth: YearMonth, nowMillis: Long): Boolean {
        val current = currentMonth(nowMillis)
        return when {
            keyMonth >= current -> true
            keyMonth == current.minusMonths(1) -> dayOfMonth(nowMillis) <= GRACE_DAYS
            else -> false
        }
    }

    /** Entering a key: it must unlock now, and may not be more than one month ahead. */
    fun acceptsOnEntry(keyMonth: YearMonth, nowMillis: Long): Boolean =
        isAllowed(keyMonth, nowMillis) && keyMonth <= currentMonth(nowMillis).plusMonths(1)

    /** True while the previous month's key is only working on grace days. */
    fun inGrace(keyMonth: YearMonth, nowMillis: Long): Boolean = keyMonth < currentMonth(nowMillis) && isAllowed(keyMonth, nowMillis)

    fun describe(month: YearMonth): String = "${month.year}年${month.monthValue}月"
}
