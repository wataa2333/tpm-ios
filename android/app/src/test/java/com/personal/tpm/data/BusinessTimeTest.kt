package com.personal.tpm.data

import java.time.Instant
import java.util.Date
import java.util.TimeZone
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class BusinessTimeTest {
    private val now = Date.from(Instant.parse("2026-11-02T15:37:00Z"))

    @Test fun deviceZoneCannotShiftFactoryDatesByEightHours() {
        val original = TimeZone.getDefault()
        try {
            listOf("UTC", "America/Los_Angeles", "Asia/Shanghai").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-11-02 23:37", serverDate(now))
                assertEquals(now, parsedDate(Jv("2026-11-02 23:37")))
                assertEquals(now, parsedDate(Jv(JSONArray(listOf(2026, 11, 2, 23, 37)))))
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun explicitIsoOffsetsAreNotTruncatedToMidnight() {
        assertEquals(now, parsedDate(Jv("2026-11-02T15:37:00Z")))
        assertEquals(now, parsedDate(Jv("2026-11-02T23:37:00+08:00")))
        assertNull(parsedBusinessDateOrNull(Jv("2026-11-02 bad timestamp")))
        assertNull(parsedBusinessDateOrNull(Jv("2026-02-30 12:00")))
    }
    @Test fun signTimeBecomesValidPrefillInsteadOfAnApparentFutureTime() {
        val repair = Jv(Jv.obj("signTime" to JSONArray(listOf(2026, 11, 2, 23, 19))))
        assertEquals("2026-11-02 23:19" to "2026-11-02 23:37", repairTimeRange(repair, now))
    }
    @Test fun futureOrReversedDraftsFallBackAndValidManualTimesRemain() {
        val repair = Jv(Jv.obj("signTime" to "2026-11-02 21:00"))
        assertEquals("2026-11-02 21:00" to "2026-11-02 23:37",
            repairTimeRange(repair, now, Jv("2026-11-03 01:00"), Jv("2026-11-03 02:00")))
        assertEquals("2026-11-02 21:30" to "2026-11-02 22:30",
            repairTimeRange(repair, now, Jv("2026-11-02 21:30"), Jv("2026-11-02 22:30")))
        assertEquals("2026-11-02 23:37" to "2026-11-02 23:37", repairTimeRange(Jv.NULL, now))
    }
}
