package com.personal.tpm.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The expected keys below were made by tools/license_keygen.py with this throw-away secret (NOT the real one), so
 * these tests check that the Kotlin algorithm gives exactly what the PC tool gives.
 */
class LicenseTests {
    private val secret = LicenseSecret.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")!!

    private fun millis(y: Int, m: Int, d: Int, h: Int = 12) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    // ---- the key ----

    @Test fun matchesTheKeysThePcToolMakes() {
        assertEquals("K957W2", MonthKey.generate(secret, YearMonth.of(2026, 9)))
        assertEquals("ZE6NHY", MonthKey.generate(secret, YearMonth.of(2026, 10)))
        assertEquals("R3ETSM", MonthKey.generate(secret, YearMonth.of(2026, 11)))
        assertEquals("NQJW29", MonthKey.generate(secret, YearMonth.of(2026, 12)))
        assertEquals("R2JEQ6", MonthKey.generate(secret, YearMonth.of(2027, 1)))
    }

    @Test fun everyKeyHasSixSymbolsWithALetterAndADigit() {
        for (year in 2026..2060) for (month in 1..12) {
            val key = MonthKey.generate(secret, YearMonth.of(year, month))
            assertEquals(6, key.length)
            assertTrue(key, key.any { it.isDigit() } && key.any { it.isLetter() })
            assertTrue(key, key.all { it in MonthKey.ALPHABET })
        }
    }

    @Test fun typedKeyIgnoresCaseSpacesAndDashes() {
        assertEquals("R3ETSM", MonthKey.normalize("r3e-tsm"))
        assertEquals("R3ETSM", MonthKey.normalize(" R3E TSM\n"))
    }

    @Test fun badSecretIsNull() {
        assertNull(LicenseSecret.parseHex("not hex"))
        assertNull(LicenseSecret.parseHex("00"))
    }

    // ---- months ----

    @Test fun monthRulesWithThreeGraceDays() {
        val nov = YearMonth.of(2026, 11)
        assertTrue(LicensePolicy.isAllowed(nov, millis(2026, 11, 1, 0)))
        assertTrue(LicensePolicy.isAllowed(nov, millis(2026, 11, 30, 23)))
        assertTrue(LicensePolicy.isAllowed(nov, millis(2026, 12, 3, 23)))
        assertTrue(!LicensePolicy.isAllowed(nov, millis(2026, 12, 4, 0)))
        assertTrue(!LicensePolicy.isAllowed(nov, millis(2027, 1, 1, 0)))
        assertTrue(LicensePolicy.isAllowed(YearMonth.of(2026, 12), millis(2026, 11, 20)))
    }

    @Test fun monthTurnsAtMidnightChinaTimeNotUtc() {
        assertTrue(!LicensePolicy.isAllowed(YearMonth.of(2026, 11), millis(2026, 12, 4, 0) + 30 * 60_000))
        assertTrue(LicensePolicy.isAllowed(YearMonth.of(2026, 11), millis(2026, 12, 3, 23) + 30 * 60_000))
    }

    // ---- the controller ----

    private fun controller(store: LicenseStore, now: Long, s: ByteArray? = secret) = LicenseController(store, s) { now }

    @Test fun noKeyMeansNeedKey() {
        assertTrue(controller(MemoryLicenseStore(), millis(2026, 11, 5)).evaluate() is LicenseState.NeedKey)
    }

    @Test fun thisMonthsKeyUnlocksThatMonthAndGraceEndsOnTheFourth() {
        val store = MemoryLicenseStore()
        assertEquals(SubmitResult.Accepted(YearMonth.of(2026, 11)), controller(store, millis(2026, 11, 5)).submit("r3etsm"))
        assertTrue(controller(store, millis(2026, 11, 20)).evaluate() is LicenseState.Allowed)
        val grace = controller(store, millis(2026, 12, 2)).evaluate() as LicenseState.Allowed
        assertNotNull(grace.notice)
        assertTrue(controller(store, millis(2026, 12, 4)).evaluate() is LicenseState.NeedKey)
    }

    @Test fun nextMonthsKeyCanBeEnteredEarlyAndTwoMonthsAheadCannot() {
        val c = controller(MemoryLicenseStore(), millis(2026, 11, 28))
        assertEquals(SubmitResult.Accepted(YearMonth.of(2026, 12)), c.submit("NQJW29"))
        assertTrue(c.submit("R2JEQ6") is SubmitResult.Rejected)
    }

    @Test fun lastMonthsKeyWorksOnlyInTheFirstThreeDays() {
        assertTrue(controller(MemoryLicenseStore(), millis(2026, 11, 2)).submit("ZE6NHY") is SubmitResult.Accepted)
        assertTrue(controller(MemoryLicenseStore(), millis(2026, 11, 4)).submit("ZE6NHY") is SubmitResult.Rejected)
    }

    @Test fun wrongOrShortKeysAreRefused() {
        val c = controller(MemoryLicenseStore(), millis(2026, 11, 5))
        assertTrue(c.submit("AAAAAA") is SubmitResult.Rejected)
        assertTrue(c.submit("R3ETS") is SubmitResult.Rejected)
        assertTrue(c.submit("") is SubmitResult.Rejected)
    }

    @Test fun anEarlierMonthNeverReplacesALaterOne() {
        val store = MemoryLicenseStore()
        val c = controller(store, millis(2026, 11, 28))
        c.submit("NQJW29")      // December, early
        c.submit("R3ETSM")      // November
        assertEquals("2026-12", store.load().month)
    }

    @Test fun missingSecretAsksForARebuild() {
        val c = controller(MemoryLicenseStore(), millis(2026, 11, 5), s = null)
        assertTrue(c.evaluate() is LicenseState.NeedKey)
        assertTrue(c.submit("R3ETSM") is SubmitResult.Rejected)
    }
}
