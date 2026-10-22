package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RepairExecutorsTest {
    @Test fun experimentalConcurrencyActuallyBoundsInFlightDocuments() = runTest {
        for(level in listOf(1,3,5,8)) {
            var current=0;var peak=0
            val result=resolveRepairExecutors(RepairPendingSnapshot(documents=(1..10).map{document("$it","SIGNED")}),concurrency=level,
                detail={id->current++;peak=maxOf(peak,current);try{delay(10);detail(id,"SIGNED")}finally{current--}},repairInfo={Jv.NULL})
            assertEquals(level,peak)
            assertEquals(10,result.executorChecks.size)
        }
    }
    @Test fun evenRowsWithAnExecutorAreCheckedAgainstTheirDetail() = runTest {
        val listed=document(state="SIGNED").copy(personKey="user:listed",personName="列表用户")
        var calls=0
        val result=resolveRepairExecutors(RepairPendingSnapshot(documents=listOf(listed)),detail={calls++;detail(state="SIGNED")},repairInfo={Jv.NULL})
        assertEquals(1,calls)
        assertNull(result.documents.single().personKey)
        assertEquals("执行人未明确",result.documents.single().personName)
    }
    private fun document(id: String = "1", state: String = "REPAIRED") = managedDocument(ManagedKind.Fault, detail(id,state),state,null)!!
    private fun person(account: String) = Jv.obj("userName" to account,"realName" to "同名")
    private fun detail(id: String = "1", state: String = "REPAIRED") = Jv(Jv.obj("faultId" to id,"faultState" to state,"user" to person("receiver"),"applyUser" to person("reporter"),"taskInfo" to Jv.obj("id" to "task1")))
    @Test fun signedUsesAssignedRepairTaskBeforeSignTimeAndNeverReceiver() {
        assertNull(repairExecutorDocument(document(state="SIGNED"),detail(state="SIGNED"),null))
        val record=Jv(Jv.obj("user" to person("assigned"),"assetFault" to Jv.obj("faultId" to "1")))
        assertEquals("user:assigned",repairExecutorDocument(document(state="SIGNED"),detail(state="SIGNED"),record)!!.personKey)
    }
    @Test fun originalAppFirstRepairRecordResolvesBothPendingStates() = runTest {
        for(state in listOf("SIGNED","REPAIRED")) {
            val response=Jv(org.json.JSONArray(listOf(Jv.obj("user" to person("current"),"assetFault" to Jv.obj("faultId" to "1")),Jv.obj("user" to person("older")))))
            val result=resolveRepairExecutors(RepairPendingSnapshot(documents=listOf(document(state=state))),detail={detail(state=state)},repairInfo={latestRepair(response) ?: Jv.NULL})
            assertEquals("user:current",result.documents.single().personKey)
            assertEquals("已核对",result.executorChecks.values.single())
        }
    }
    @Test fun detailExecutorAndCurrentRepairPrimaryUserAreAllowedButHelpersAreNot() {
        val order = detail()
        (order.raw as org.json.JSONObject).put("repairUser",person("alice"))
        assertEquals("user:alice",repairExecutorDocument(document(),order,null)!!.personKey)
        val repair = Jv(Jv.obj("user" to person("bob"),"repairUsers" to org.json.JSONArray(listOf(person("helper"))),"assetFault" to Jv.obj("faultId" to "1","taskInfo" to Jv.obj("id" to "task1"))))
        assertEquals("user:bob",repairExecutorDocument(document(),detail(),repair)!!.personKey)
        assertNull(repairExecutorDocument(document(),detail(),Jv(Jv.obj("repairUsers" to repair["repairUsers"].raw))))
    }
    @Test fun mismatchedOrderOrTaskCannotAssignSomeoneElse() {
        assertThrows(IllegalArgumentException::class.java) { repairExecutorDocument(document(),detail("2"),null) }
        assertThrows(IllegalArgumentException::class.java) { repairExecutorDocument(document(),detail(),Jv(Jv.obj("user" to person("wrong"),"assetFault" to Jv.obj("taskInfo" to Jv.obj("id" to "old-task"))))) }
    }
    @Test fun boundedRequestsAreCachedAndOnlyFailuresRetry() = runTest {
        val initial = RepairPendingSnapshot(documents = (1..8).map { document("$it","SIGNED") })
        var concurrent = 0; var peak = 0; var calls = 0
        val read: suspend (String) -> Jv = { id ->
            calls++; concurrent++; peak = maxOf(peak,concurrent)
            try { delay(10); if (id == "2") error("offline"); detail(id,"SIGNED") } finally { concurrent-- }
        }
        val first = resolveRepairExecutors(initial,detail=read,repairInfo={ Jv.NULL })
        assertEquals(3,peak); assertEquals(8,calls)
        val cached = resolveRepairExecutors(first,detail=read,repairInfo={ Jv.NULL })
        assertEquals(8,calls)
        val retried = resolveRepairExecutors(cached,retryFailures=true,detail={ id -> calls++; Jv((detail(id,"SIGNED").raw as org.json.JSONObject).put("repairUser",person("fixed"))) },repairInfo={ Jv.NULL })
        assertEquals(9,calls)
        assertEquals("user:fixed",retried.documents.single { it.id == "2" }.personKey)
    }
    @Test fun changedStateIsExcludedAndScopeCancellationPropagates() = runTest {
        val initial = RepairPendingSnapshot(documents=listOf(document(state="SIGNED")))
        val result = resolveRepairExecutors(initial,detail={detail(state="REPAIRED")},repairInfo={ error("must not request") })
        assertTrue(result.documents.isEmpty())
        assertTrue(result.pages.all { it.warnings.isNotEmpty() })
        try {
            resolveRepairExecutors(initial,checkScope={ throw CancellationException() },detail={ error("must not request") },repairInfo={ Jv.NULL })
            fail("scope changes must cancel")
        } catch (_: CancellationException) { }
    }
}
