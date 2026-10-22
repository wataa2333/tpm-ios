package com.personal.tpm.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RepairReadEstimateTest {
    @Test fun monthlyTotalsRespectPartialMonthsAndAllIsNotADoubleCount() {
        val span=DateSpan(LocalDate.of(2026,1,15),LocalDate.of(2026,3,5))
        val docs=listOf(doc("1","user:a","2026-01-20"),doc("1","user:a","2026-01-20"),doc("2","user:b","2026-01-25"),doc("3",null,"2026-03-01"),doc("outside","user:a","2026-01-01"))
        val trends=repairTrends(docs,span,true,monthly=true,includeAll=true)
        val all=trends.first()
        assertEquals(ALL_REPAIR_TREND,all.key)
        assertEquals(listOf(LocalDate.of(2026,1,1),LocalDate.of(2026,2,1),LocalDate.of(2026,3,1)),all.dates)
        assertEquals(listOf(2,0,1),all.counts)
        assertEquals(listOf(2,null,1),repairTrends(docs,span,false,monthly=true,includeAll=true).first().counts)
        assertEquals(3,all.counts.sumOf{it ?: 0})
    }
    @Test fun tickLabelsAreBoundedAndAlwaysIncludeBothEnds() {
        assertEquals(emptyList<Int>(),repairChartTicks(0,5))
        assertEquals(listOf(0),repairChartTicks(1,5))
        for(points in listOf(7,30,90,365)) {
            val ticks=repairChartTicks(points,5)
            assertEquals(0,ticks.first())
            assertEquals(points-1,ticks.last())
            assertTrue(ticks.size<=5)
            assertEquals(ticks.sorted().distinct(),ticks)
        }
    }
    @Test fun preflightOnlyReadsCountsAndUnknownIsNotZero() = runTest {
        val calls=mutableListOf<String>()
        val estimate=estimateRepairRead(listOf("SIGNED","REPAIRED")){state->calls+=state;Jv(Jv.obj("content" to JSONArray(),"totalElements" to if(state=="SIGNED") 3000 else 100,"last" to false))}
        assertEquals(3100L,estimate.total)
        assertEquals(listOf("SIGNED","REPAIRED"),calls)
        val unknown=estimateRepairRead(listOf("SIGNED")){Jv(Jv.obj("content" to JSONArray(),"last" to false))}
        assertNull(unknown.total)
        assertFalse(unknown.errors.isEmpty())
    }
    private fun doc(id:String,key:String?,countDate:String="2026-10-21")=ManagedDocument(ManagedKind.Fault,id,"","",ManagedPhase.Processed,key,"同名","",countDate)
    @Test fun quantitySortSupportsBothDirectionsAndStableAccounts() {
        val docs=listOf(doc("1","user:a"),doc("2","user:a"),doc("3","user:b"))
        assertEquals(listOf("user:a","user:b"),sortedPersonWorkloads(docs,true,false).map{it.key})
        assertEquals(listOf("user:b","user:a"),sortedPersonWorkloads(docs,true,true).map{it.key})
        assertEquals(listOf("VERIFIED","RESOLVED","FINISHED"),acceptedRepairStates)
    }
    @Test fun trendsUseEveryDateDeduplicateAndDistinguishUnknownFromZero() {
        val span=DateSpan(LocalDate.of(2026,10,20),LocalDate.of(2026,10,21))
        val docs=listOf(doc("1","user:a"),doc("1","user:a"),doc("2","user:b","2026-10-20"))
        val complete=repairTrends(docs,span,true)
        assertEquals(listOf(0,1),complete.single{it.key=="user:a"}.counts)
        assertEquals(listOf(1,0),complete.single{it.key=="user:b"}.counts)
        assertEquals(listOf(null,1),repairTrends(docs,span,false).single{it.key=="user:a"}.counts)
    }
}
