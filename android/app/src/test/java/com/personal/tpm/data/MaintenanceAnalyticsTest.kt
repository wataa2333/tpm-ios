package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MaintenanceAnalyticsTest {
    private val span = DateSpan(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 6))
    private fun row(id: String, state: String, claimant: String? = "u1", date: String = "2026-11-05 09:00") = Jv.obj(
        "maintainId" to id, "maintainState" to state, "maintainName" to "保养$id", "sn" to "MA$id", "endTime" to date,
        "maintainUser" to claimant?.let { Jv.obj("userName" to it, "realName" to "同名") },
        "user" to Jv.obj("userName" to "receiver"), "applyUser" to Jv.obj("userName" to "reporter"))
    private fun response(rows: List<JSONObject>, last: Boolean = true) = Jv(Jv.obj("content" to JSONArray(rows), "last" to last))
    private fun state(filters: List<JSONObject>) = filters.first().getString("value")

    @Test fun maintenanceStatesUseTheirOwnCodesAndFilters() {
        assertEquals(listOf("REPORTED", "RECEIVED", "SIGNED", "MAINTAINED", "VERIFIED", "RESOLVED", "FINISHED"), MaintenanceState.home.map { it.code })
        assertEquals("待抢单", maintenanceStateName("RECEIVED"))
        val filter = maintenanceStateFilters("MAINTAINED").single()
        assertEquals("maintainState", filter.getString("field"))
        assertEquals("MAINTAINED", filter.getString("value"))
        assertTrue(maintenanceStateFilters("").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { maintenanceStateFilters("REPAIRED") }
        assertEquals(MaintenanceState.home.size, MaintenanceState.home.map { it.countKey }.toSet().size)
    }

    @Test fun pendingUsesClaimantsAndDoesNotUseRepairOrDateFilters() = runTest {
        val report = loadMaintenanceReport(false, span, page = { filters, _, _ ->
            assertEquals(1, filters.size)
            assertEquals("maintainState", filters.first().getString("field"))
            response(listOf(row(state(filters), state(filters), if (state(filters) == "SIGNED") "u1" else "u2")))
        })
        assertNull(report.span)
        assertTrue(report.complete)
        assertEquals(2, report.people.size)
        assertEquals(2, report.people.sumOf { it.pending })
        assertEquals(setOf("user:u1", "user:u2"), report.people.map { it.key }.toSet())
    }

    @Test fun processedFiltersByMaintenanceEndTimeAndPublishesAllThreeStates() = runTest {
        val progress = mutableListOf<MaintenanceReport>()
        val report = loadMaintenanceReport(true, span, progress = { progress += it }, page = { filters, _, _ ->
            assertEquals("endTime", filters[1].getString("field"))
            assertEquals("dateBetween", filters[1].getString("operator"))
            response(listOf(row(state(filters), state(filters))))
        })
        assertEquals(3, progress.size)
        assertFalse(progress.first().complete)
        assertTrue(report.complete)
        assertEquals(3, report.people.single().processed)
        assertEquals(3, repairTrends(report.documents, span, true).single().counts.sumOf { it ?: 0 })
    }

    @Test fun missingClaimantIsNeverReplacedByReceiverOrReporter() = runTest {
        val report = loadMaintenanceReport(false, null, page = { filters, _, _ -> response(listOf(row(state(filters), state(filters), claimant = null))) })
        assertNull(report.people.single().key)
        assertEquals(2, report.people.single().pending)
    }

    @Test fun wrongDatesAndUnexpectedStatesAreExcludedAndReportedAsIncomplete() = runTest {
        val report = loadMaintenanceReport(true, span, page = { filters, _, _ ->
            response(listOf(row("outside", state(filters), date = "2026-10-01 09:00"), row("wrong", "SIGNED")))
        })
        assertFalse(report.complete)
        assertTrue(report.documents.isEmpty())
        assertTrue(report.slices.all { it.message.isNotBlank() })
        assertTrue(repairTrends(report.documents, span, false, includeAll = true).single().counts.all { it == null })
    }

    @Test fun movingOrdersAreDeduplicatedAndCannotMasqueradeAsACompleteSnapshot() = runTest {
        val report = loadMaintenanceReport(true, span, page = { filters, _, _ -> response(listOf(row("same", state(filters)))) })
        assertTrue(report.duplicated)
        assertFalse(report.complete)
        assertEquals(1, report.documents.size)
        assertEquals(1, report.people.single().processed)
    }

    @Test fun failedStateDoesNotEraseOtherStateResultsOrTurnIntoZero() = runTest {
        val report = loadMaintenanceReport(false, null, page = { filters, _, _ ->
            if (state(filters) == "SIGNED") error("offline") else response(listOf(row("1", state(filters))))
        })
        assertFalse(report.complete)
        assertEquals(1, report.documents.size)
        assertFalse(report.slices.first().complete)
        assertEquals("offline", report.slices.first().message)
    }

    @Test fun pagingReadsBeyondTwoThousandWithoutSilentlyTruncating() = runTest {
        val offsets = mutableListOf<Int>()
        val report = loadMaintenanceReport(false, null, page = { filters, offset, limit ->
            if (state(filters) != "SIGNED") response(emptyList()) else {
                offsets += offset
                assertEquals(100, limit)
                val end = minOf(offset + limit, 2107)
                response((offset until end).map { row(it.toString(), "SIGNED") }, last = end == 2107)
            }
        })
        assertEquals(2107, report.documents.size)
        assertTrue(report.complete)
        assertEquals(2100, offsets.last())
    }

    @Test fun cancellationStopsBeforeAnyFurtherPage() = runTest {
        var requests = 0
        try {
            loadMaintenanceReport(false, null, checkScope = { throw CancellationException("factory changed") }, page = { _, _, _ -> requests++; response(emptyList()) })
            fail()
        } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }
}
