package com.personal.tpm.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CompletedOrdersTest {
    private val start = Instant.parse("2026-11-12T00:00:00Z")
    private val day = completionDay(start)

    @Test fun theBusinessDaySwitchesAtEightInChina() {
        val before = completionDay(start.minusSeconds(1))
        assertEquals(start, before.end)
        assertEquals(start, day.start)
        assertEquals(Instant.parse("2026-11-13T00:00:00Z"), day.end)
        assertTrue(start.minusSeconds(1) in before)
        assertFalse(start in before)
        assertTrue(start in day)
        assertFalse(day.end in day)
    }

    @Test fun missingEndTimesAndInitiatorStepsAreNotCompletions() {
        assertNull(completedOrder(task("init", "A", "2026-11-12 09:00", node = "ACT_INITIATOR")))
        assertNull(completedOrder(task("pending", "A", "")))
    }

    @Test fun countsAnOrderOnceAcrossPagesAndStopsBelowTheDayBoundary() = runTest {
        val offsets = mutableListOf<Int>()
        val reader = CompletedOrdersReader { offset ->
            offsets += offset
            when (offset) {
                0 -> page(false, task("next-day", "D", "2026-11-13 08:00"), task("A-1", "A", "2026-11-12 09:00"))
                2 -> page(false, task("A-2", "A", "2026-11-12 08:50"), task("B-1", "B", "2026-11-12 08:00"),
                    task("previous-day", "C", "2026-11-12 07:59"))
                else -> error("Already below the business day")
            }
        }
        assertEquals(2, countCompletedOrders(reader, day))
        assertEquals(listOf(0, 2), offsets)
    }

    @Test fun aNonDescendingPageDoesNotEndTheDayScanEarly() = runTest {
        val reader = CompletedOrdersReader { offset ->
            if (offset == 0) page(false, task("old-1", "old1", "2026-11-12 07:45"), task("old-2", "old2", "2026-11-12 07:55"))
            else page(true, task("new", "new", "2026-11-12 09:00"))
        }
        assertEquals(1, countCompletedOrders(reader, day))
    }

    private fun task(id: String, order: String, end: String, node: String = "complete") = Jv(Jv.obj(
        "id" to id, "businessKey" to order, "processDefinitionKey" to "MAINTAIN_STANDARD",
        "endTime" to end, "taskDefinitionKey" to node,
    ))

    private fun page(last: Boolean, vararg rows: Jv) =
        Jv(Jv.obj("last" to last, "content" to JSONArray(rows.map { it.raw })))
}
