package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RepairPendingTest {
    @Test fun experimentalPageSizesKeepOffsetsStableAcrossPauseAndResume() = runTest {
        for(size in listOf(50,100,200,500)) {
            val calls=mutableListOf<Triple<String,Int,Int>>()
            val total=size*2+7
            val read:suspend(String,Int,Int)->Jv={state,offset,limit->
                calls+=Triple(state,offset,limit)
                response(state,offset/limit*limit,limit,total)
            }
            val first=loadRepairPending(pageSize=size,budget=size*3,page=read)
            assertFalse(first.exhausted)
            assertEquals(size,first.pageSize)
            val resumed=loadRepairPending(first,budget=null,page=read)
            assertTrue(resumed.complete)
            assertEquals(total*2,resumed.documents.size)
            assertTrue(calls.all{it.third==size && it.second%size==0})
            try {
                loadRepairPending(first,pageSize=if(size==100) 200 else 100,page=read)
                fail("resuming with another page size must be rejected")
            } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun serverCappingAnExperimentalPageDoesNotMasqueradeAsComplete() = runTest {
        val result=loadRepairPending(pageSize=500,budget=null) { state,offset,_ -> response(state,offset,100,400) }
        assertFalse(result.complete)
        assertTrue(result.pages.all{it.warnings.any{warning->"500" in warning && "100" in warning}})
    }
    @Test fun processedStateChangesKeepTheLastObservationWithoutDuplicateWarnings() = runTest {
        val span = DateSpan(java.time.LocalDate.of(2026,10,15),java.time.LocalDate.of(2026,10,21))
        val initial = RepairPendingSnapshot(pages=listOf(RepairPendingPage("RESOLVED",expected=5),RepairPendingPage("FINISHED")))
        val result=loadRepairPending(initial,span=span) { state,_,_ ->
            Jv(Jv.obj("content" to JSONArray(listOf(row(1,state,if(state=="RESOLVED") "old" else "latest").put("faultTime","2026-10-20 12:00"))),
                "totalElements" to if(state=="RESOLVED") 1 else 3,"last" to true))
        }
        assertTrue(result.complete)
        assertEquals(1,result.documents.size)
        assertEquals("user:latest",result.documents.single().personKey)
        assertTrue(result.pages.all{it.warnings.isEmpty()})
    }
    @Test fun laterProcessedObservationOutsideTheScopeRemovesTheEarlierRecord() = runTest {
        val span = DateSpan(java.time.LocalDate.of(2026,10,15),java.time.LocalDate.of(2026,10,21))
        val initial=RepairPendingSnapshot(pages=listOf(RepairPendingPage("RESOLVED"),RepairPendingPage("FINISHED")))
        for(outsideState in listOf(false,true)) {
            val result=loadRepairPending(initial,span=span) { state,_,_ ->
                val later=state=="FINISHED"
                val actual=if(later && outsideState) "SIGNED" else state
                val end=if(later && !outsideState) "2026-10-01 12:00" else "2026-10-20 12:00"
                Jv(Jv.obj("content" to JSONArray(listOf(row(1,actual).put("faultTime",end))),"last" to true))
            }
            assertTrue(result.complete)
            assertTrue(result.documents.isEmpty())
        }
    }
    @Test fun unevenStateTotalsNeverChangePageSizeToFillBatchBudget() = runTest {
        val limits = mutableListOf<Int>()
        val read: suspend (String,Int,Int) -> Jv = { state,offset,limit ->
            limits += limit
            // Emulate the server's conversion to a Spring page index.
            response(state,offset / limit * limit,limit,if (state == "SIGNED") 11 else 600)
        }
        val first = loadRepairPending(page=read)
        assertTrue(first.readCount <= 500)
        assertFalse(first.exhausted)
        val resumed = loadRepairPending(first,page=read)
        assertTrue(resumed.complete)
        assertEquals(611,resumed.documents.size)
        assertEquals(setOf(100),limits.toSet())
    }
    private fun row(id: Int, state: String, account: String? = "u$id") = Jv.obj(
        "faultId" to "$id", "faultState" to state,
        "repairUser" to Jv.obj("realName" to "同名", "userName" to account),
        "user" to Jv.obj("userName" to "receiver"),
    )
    private fun response(state: String, offset: Int, limit: Int, total: Int): Jv {
        val end = minOf(offset + limit, total)
        return Jv(Jv.obj("content" to JSONArray((offset until end).map { row(it + if (state == "SIGNED") 0 else 10000, state) }), "totalElements" to total, "last" to (end >= total)))
    }
    @Test fun fiveHundredBudgetPausesAndResumesBothStatesWithoutDetailRequests() = runTest {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val read: suspend (String, Int, Int) -> Jv = { state, offset, limit ->
            calls += Triple(state, offset, limit); response(state, offset, limit, 300)
        }
        val first = loadRepairPending(page = read)
        assertEquals(500, first.readCount)
        assertFalse(first.complete)
        assertEquals(setOf("SIGNED", "REPAIRED"), calls.map { it.first }.toSet())
        val resumed = loadRepairPending(first, page = read)
        assertTrue(resumed.complete)
        assertEquals(600, resumed.documents.size)
        assertEquals(6, calls.size)
        assertEquals(600, personnelWorkloads(resumed.documents).sumOf { it.pending })
    }
    @Test fun missingIdentityAndSameNameAccountsDoNotMerge() = runTest {
        val result = loadRepairPending { state, _, _ ->
            Jv(Jv.obj("content" to JSONArray(if (state == "SIGNED") listOf(row(1,state,"alice"),row(2,state,"bob"),row(3,state,null)) else emptyList<Any>()), "last" to true))
        }
        val people = personnelWorkloads(result.documents)
        assertEquals(3, people.size)
        assertEquals("执行人未明确", people.single { it.key == null }.name)
        assertTrue(result.complete)
    }
    @Test fun failureAndCancellationRetainLastPublishedPageForRetry() = runTest {
        var published = RepairPendingSnapshot()
        try {
            loadRepairPending(progress = { published = it }) { state, offset, limit ->
                if (state == "REPAIRED") error("network")
                response(state, offset, limit, 200)
            }
            fail("failure must be reported")
        } catch (_: IllegalStateException) { }
        assertEquals(100, published.documents.size)
        val resumed = loadRepairPending(published) { state, offset, limit -> response(state, offset, limit, 200) }
        assertTrue(resumed.complete)
        assertEquals(400, resumed.documents.size)
        try {
            loadRepairPending(checkScope = { throw CancellationException() }) { _,_,_ -> error("must not request") }
            fail("scope cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun changedTotalsAndStateMismatchesAreIncompleteAndDeduplicated() = runTest {
        val result = loadRepairPending { state, offset, limit ->
            if (state == "REPAIRED") Jv(Jv.obj("content" to JSONArray(listOf(row(1, "SIGNED"))), "last" to true))
            else if (offset == 0) response(state,offset,limit,101)
            else Jv(Jv.obj("content" to JSONArray(listOf(row(1,state))), "last" to true, "totalElements" to 102))
        }
        assertTrue(result.exhausted)
        assertFalse(result.complete)
        assertEquals(100,result.documents.size)
        assertTrue(result.pages.first().warnings.any { "总数发生变化" in it })
    }
    @Test fun unlimitedReadContinuesBeyondTwoThousandPerState() = runTest {
        val snapshot=loadRepairPending(budget=null) { state,offset,limit -> response(state,offset,limit,2500) }
        assertEquals(5000,snapshot.readCount)
        assertTrue(snapshot.exhausted)
        assertTrue(snapshot.complete)
        assertTrue(snapshot.pages.all { it.warnings.isEmpty() })
    }
    @Test fun processedSnapshotUsesOnlyChosenEndDatesAndUniqueBusinessOrders() = runTest {
        val span = DateSpan(java.time.LocalDate.of(2026,10,15),java.time.LocalDate.of(2026,10,21))
        val initial = RepairPendingSnapshot(pages = listOf("VERIFIED","RESOLVED","FINISHED").map { RepairPendingPage(it) })
        val result = loadRepairPending(initial, span = span) { state,_,_ ->
            val rows = when (state) {
                "VERIFIED" -> listOf(row(1,state).put("faultTime","2026-10-20 12:00"))
                "RESOLVED" -> listOf(row(1,state).put("faultTime","2026-10-20 12:00"))
                else -> listOf(row(2,state).put("faultTime","2026-10-01 12:00"),row(3,state))
            }
            Jv(Jv.obj("content" to JSONArray(rows),"last" to true))
        }
        assertFalse(result.complete)
        assertEquals(1,result.documents.size)
        assertEquals(1,personnelWorkloads(result.documents).sumOf { it.processed })
        assertTrue(result.pages.last().warnings.any { "日期" in it })
    }
}
