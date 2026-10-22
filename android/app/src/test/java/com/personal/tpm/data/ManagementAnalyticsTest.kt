package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ManagementAnalyticsTest {
    private fun row(kind: ManagedKind, id: String, state: String, person: String = "user1", end: String = "2026-10-21 10:00") = Jv(Jv.obj(
        kind.idField to id, kind.stateField to state, kind.nameField to "工单$id", "sn" to "SN$id", kind.personField to Jv.obj("userName" to person, "realName" to "同名用户"), kind.endField to end,
        "applyUser" to Jv.obj("userName" to "reporter"), "asset" to Jv.obj("assetName" to "机床")
    ))
    private fun page(rows: List<Jv>, last: Boolean, total: Int? = null) = Jv(Jv.obj("content" to JSONArray(rows.map { it.raw }), "last" to last).also { if (total != null) it.put("totalElements", total) })
    @Test fun statesAndExecutorFieldsMatchOriginalClient() {
        assertEquals("REPAIRED", ManagedKind.Fault.workingState)
        assertEquals("MAINTAINED", ManagedKind.Maintain.workingState)
        assertEquals("PRECISED", ManagedKind.Precise.workingState)
        assertEquals("repairUser", ManagedKind.Fault.personField)
        assertEquals("maintainUser", ManagedKind.Maintain.personField)
        assertEquals("user", ManagedKind.Precise.personField)
        ManagedKind.entries.forEach { assertEquals(ManagedPhase.Sign, managedPhase(it, "SIGNED")); assertEquals(ManagedPhase.Finish, managedPhase(it, it.workingState)) }
    }
    @Test fun receiverAndReporterAreNeverUsedAsExecutorFallback() {
        val value = Jv(Jv.obj("faultId" to "1", "faultState" to "SIGNED", "user" to Jv.obj("userName" to "receiver"), "applyUser" to Jv.obj("userName" to "reporter")))
        assertNull(managedDocument(ManagedKind.Fault, value, "SIGNED", null)!!.personKey)
    }
    @Test fun sameNameDifferentAccountsRemainSeparateAndDocumentsAreDeduplicated() {
        val a = managedDocument(ManagedKind.Fault, row(ManagedKind.Fault, "a", "SIGNED", "alice"), "SIGNED", null)!!
        val b = managedDocument(ManagedKind.Maintain, row(ManagedKind.Maintain, "a", "MAINTAINED", "bob"), "MAINTAINED", null)!!
        val stats = personnelWorkloads(listOf(a, a, b))
        assertEquals(2, stats.size)
        assertEquals(2, stats.sumOf { it.pending })
    }
    @Test fun completedUsesEndDateAndExcludesMissingOrOutOfRangeDates() {
        val span = DateSpan(LocalDate.of(2026,10,20), LocalDate.of(2026,10,21))
        assertNotNull(managedDocument(ManagedKind.Fault, row(ManagedKind.Fault,"1","FINISHED"), "FINISHED", span))
        assertNull(managedDocument(ManagedKind.Fault, row(ManagedKind.Fault,"1","FINISHED",end=""), "FINISHED", span))
        assertNull(managedDocument(ManagedKind.Fault, row(ManagedKind.Fault,"1","FINISHED",end="2026-10-19 10:00"), "FINISHED", span))
        val filters = workloadFilters(ManagedKind.Fault,"FINISHED",span)
        assertEquals("repairEndTime", filters[1].getString("field"))
        assertEquals("dateBetween", filters[1].getString("operator"))
    }
    @Test fun readsAllPagesWithOffsetsAndKeepsUnknownTotalsValid() = runTest {
        val offsets = mutableListOf<Int>()
        val result = collectWorkload(ManagedKind.Fault,"SIGNED",null, page = { offset, _ ->
            offsets += offset
            page(listOf(row(ManagedKind.Fault,offset.toString(),"SIGNED")), offset == 1)
        }, limit=1)
        assertEquals(listOf(0,1),offsets)
        assertTrue(result.complete)
        assertEquals(2,result.documents.size)
    }
    @Test fun failureRetainsLoadedRowsButMarksIncomplete() = runTest {
        val result = collectWorkload(ManagedKind.Fault,"SIGNED",null,page={ offset, _ ->
            if (offset == 0) page(listOf(row(ManagedKind.Fault,"1","SIGNED")),false) else error("network")
        },limit=1)
        assertFalse(result.complete)
        assertEquals(1,result.documents.size)
        assertTrue(result.message.contains("network"))
    }
    @Test fun capAndRepeatedPageCannotBePresentedAsComplete() = runTest {
        val cap = collectWorkload(ManagedKind.Fault,"SIGNED",null,page={ _,_ -> page(listOf(row(ManagedKind.Fault,"1","SIGNED")),false) },maxRows=1,limit=1)
        assertFalse(cap.complete)
        var calls = 0
        val repeated = collectWorkload(ManagedKind.Fault,"SIGNED",null,page={ _,_ -> calls++; page(listOf(row(ManagedKind.Fault,"1","SIGNED")),false) },limit=1)
        assertFalse(repeated.complete)
        assertEquals(2,calls)
    }
    @Test fun unexpectedStatesAndInconsistentTotalsAreFlagged() = runTest {
        val result = collectWorkload(ManagedKind.Fault,"SIGNED",null,page={ _,_ -> page(listOf(row(ManagedKind.Fault,"1","REPAIRED")),true,10) })
        assertFalse(result.complete)
        assertTrue(result.documents.isEmpty())
    }
    @Test fun factoryOrAccountCancellationIsNotConvertedToEmptySuccess() = runTest {
        try {
            collectWorkload(ManagedKind.Fault,"SIGNED",null,page={ _,_ -> error("must not request") },checkScope={ throw CancellationException("scope changed") })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun directPersonnelQueryUsesVerifiedExecutorFieldAndExactName() {
        ManagedKind.entries.forEach { kind ->
            val filters = personnelFilters(kind, "SIGNED", null, " 张三 ")
            assertEquals(2, filters.size)
            assertTrue(filters.last().toString().contains(kind.personField + ".realName"))
            assertTrue(filters.last().toString().contains("张三"))
            assertFalse(filters.last().toString().contains("like"))
        }
        assertEquals(1, personnelFilters(ManagedKind.Fault, "SIGNED", null, "").size)
    }
    @Test fun serverTotalsDoNotRequireDownloadingAllRecords() {
        val result = page(listOf(row(ManagedKind.Fault, "a", "SIGNED")), false, 12345)
        assertEquals(12345, workloadPageTotal(result))
        assertEquals(1, result["content"].array.size)
    }
    @Test fun missingTotalIsNotMisreportedAsOne() {
        try { workloadPageTotal(page(listOf(row(ManagedKind.Fault, "a", "SIGNED")), false)); fail("should reject missing total") }
        catch (_: IllegalStateException) { }
        assertEquals(0, workloadPageTotal(page(emptyList(), true)))
    }

}
