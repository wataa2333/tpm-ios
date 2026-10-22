package com.personal.tpm.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RepairDateRangesTest {
    @Test fun thisMonthStopsTodayIncludingMonthAndLeapYearBoundaries() {
        for(value in listOf("2026-10-21","2026-01-01","2024-02-29","2026-02-28","2026-12-31")) {
            val today=LocalDate.parse(value)
            val span=currentMonthSpan(today)
            assertEquals(today.withDayOfMonth(1),span.days.first)
            assertEquals(today,span.days.second)
            assertEquals(today.dayOfMonth.toLong(),java.time.temporal.ChronoUnit.DAYS.between(span.from,span.to)+1)
        }
    }
}
