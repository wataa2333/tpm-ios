package com.personal.tpm.data

import java.time.LocalDate

data class RepairTrend(val key:String?,val name:String,val dates:List<LocalDate>,val counts:List<Int?>)
const val ALL_REPAIR_TREND="aggregate:all"
fun repairTrends(documents:List<ManagedDocument>,span:DateSpan,complete:Boolean,monthly:Boolean=false,includeAll:Boolean=false):List<RepairTrend> {
    val dates=if(monthly) generateSequence(span.days.first.withDayOfMonth(1)){it.plusMonths(1)}.takeWhile{it<=span.days.second}.toList()
        else generateSequence(span.days.first){ it.plusDays(1) }.takeWhile { it<=span.days.second }.toList()
    val unique=documents.distinctBy { it.key }.filter { it.phase==ManagedPhase.Processed }
    fun counts(docs:List<ManagedDocument>):List<Int?> {
        val values=docs.mapNotNull { runCatching{LocalDate.parse(it.end.take(10))}.getOrNull() }.filter{it>=span.days.first && it<=span.days.second}
            .map{if(monthly) it.withDayOfMonth(1) else it}.groupingBy{it}.eachCount()
        return dates.map{values[it] ?: if(complete) 0 else null}
    }
    val people=unique.groupBy { it.personKey }.map { (key,docs)->
        RepairTrend(key,if(key==null) "执行人未明确" else docs.first().personName,dates,counts(docs))
    }.sortedWith(compareByDescending<RepairTrend> { it.counts.sumOf { count->count ?: 0 } }.thenBy{it.key.orEmpty()})
    return if(includeAll) listOf(RepairTrend(ALL_REPAIR_TREND,"全部",dates,counts(unique)))+people else people
}

fun repairChartTicks(points:Int,maxLabels:Int):List<Int> {
    if(points<=0) return emptyList()
    val count=minOf(points,maxLabels.coerceAtLeast(2))
    return if(count==1) listOf(0) else (0 until count).map{(it.toDouble()*(points-1)/(count-1)).toInt()}.distinct()
}
