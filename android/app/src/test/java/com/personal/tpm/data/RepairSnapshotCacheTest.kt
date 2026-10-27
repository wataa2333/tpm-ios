package com.personal.tpm.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RepairSnapshotCacheTest {
    @Test fun legacyExcelDocumentsCannotResumeAsApiPages() {
        val doc=ManagedDocument(ManagedKind.Fault,"excel:FA1","","FA1",ManagedPhase.Processed,null,"","","2026-10-01")
        val old=RepairPendingSnapshot(acceptedRepairStates.map { RepairPendingPage(it) },listOf(doc))
        assertNull(repairSnapshotFromCache(Jv(repairSnapshotCache(old)),true))
    }
    @Test fun pendingRoundTripKeepsDistinctAccountsAndPartialProgress() {
        val doc=ManagedDocument(ManagedKind.Fault,"123","任务","FA123",ManagedPhase.Sign,"user:a","同名","机台","2026-10-22")
        val snapshot=RepairPendingSnapshot(listOf(RepairPendingPage("SIGNED",1,3),RepairPendingPage("REPAIRED",0,null)),listOf(doc),mapOf(doc.key to "已核对"),500)
        assertEquals(snapshot,repairSnapshotFromCache(Jv(repairSnapshotCache(snapshot)),false))
        assertNull(repairSnapshotFromCache(Jv(repairSnapshotCache(snapshot)),true))
    }
    @Test fun processedRoundTripKeepsDailyMonthlyAndUnknownValues() {
        val span=DateSpan(LocalDate.of(2026,10,20),LocalDate.of(2026,10,22))
        val docs=listOf(
            ManagedDocument(ManagedKind.Fault,"1","","",ManagedPhase.Processed,"user:a","同名","","2026-10-20"),
            ManagedDocument(ManagedKind.Fault,"2","","",ManagedPhase.Processed,"user:b","同名","","2026-10-22"))
        val original=RepairPendingSnapshot(acceptedRepairStates.map { RepairPendingPage(it,1,2,false) },docs,pageSize=500)
        val restored=repairSnapshotFromCache(Jv(repairSnapshotCache(original)),true)!!
        for(monthly in listOf(false,true)) assertEquals(repairTrends(original.documents,span,false,monthly,true),repairTrends(restored.documents,span,false,monthly,true))
        assertEquals(2,personnelWorkloads(restored.documents).size)
        assertFalse(restored.complete)
    }
    @Test fun invalidCacheDoesNotBecomeAnEmptyCompleteReport() {
        assertNull(repairSnapshotFromCache(Jv.NULL,false))
        assertNull(repairSnapshotFromCache(Jv(Jv.obj("pageSize" to 500)),false))
    }
}
