package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.DateSpan
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

fun DateSpan.dayCount():Long=ChronoUnit.DAYS.between(days.first,days.second)+1

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable
fun RepairDateControls(vm:RepairPendingViewModel) {
    var choosing by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(7,30,90,365).forEach { days -> ChoiceChip(vm.days==days && vm.customSpan==null && !vm.thisMonth,{vm.selectDays(days)},if(days==365) "近一年" else "近${days}天",enabled=!vm.loading) }
        ChoiceChip(vm.thisMonth,vm::selectThisMonth,"这个月",enabled=!vm.loading)
        ChoiceChip(vm.customSpan!=null,{choosing=true},"自定义",enabled=!vm.loading)
    }
    vm.selectedSpan?.let { AnimatedLabel("${it.summary} · ${it.dayCount()}天",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    AnimatedAppDialog(choosing, {choosing=false}) {
        val current=vm.selectedSpan
        fun millis(date:java.time.LocalDate)=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state=rememberDateRangePickerState(initialSelectedStartDateMillis=current?.let{millis(it.days.first)},initialSelectedEndDateMillis=current?.let{millis(it.days.second)})
        fun day(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            DateRangePicker(state,Modifier.weight(1f,fill=false).heightIn(max=440.dp),title={Text("选择故障发现日期",Modifier.padding(16.dp))})
            Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.End) {
                TextButton(onClick={choosing=false}){Text("取消")}
                TextButton(enabled=state.selectedStartDateMillis!=null,onClick={
            val from=day(state.selectedStartDateMillis!!); val to=day(state.selectedEndDateMillis ?: state.selectedStartDateMillis!!)
            vm.selectSpan(DateSpan(from,to));choosing=false
        }){Text("确定")}
            }
        }
        }
    }
}
