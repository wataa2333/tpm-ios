package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RepairQueryDialog(vm:RepairPendingViewModel) {
    var lastPreview by remember { mutableStateOf(vm.confirmation) }
    LaunchedEffect(vm.confirmation) { vm.confirmation?.let { lastPreview=it } }
    val preview=vm.confirmation ?: lastPreview ?: return
    val total=preview.total
    AnimatedMessageDialog(visible=vm.confirmation!=null,onDismissRequest=vm::dismissConfirmation,title={Text(when{total==null->"工单数量未确认";total>100 || (vm.selectedSpan?.dayCount() ?: 0)>7->"数据量偏多";else->"确认读取"})},
        text={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("预计读取 ${total?.toString() ?: "未知数量的"} 张维修工单。")
            preview.counts.forEach{(state,count)->Text("${when(state){"SIGNED"->"待签到";"REPAIRED"->"待完成";"VERIFIED"->"待确认";"RESOLVED"->"待复线";else->"已完结"}}：${count?.toString() ?: "未知"}单")}
            Text(if(vm.processed) "读取后按人员汇总，可查看统计图和统计表。" else "读取报修详情和维修任务信息，按任务执行人汇总。")
            Text("读取可能需要一些时间，可以随时暂停。")
            if(preview.errors.isNotEmpty()) Text("部分总数获取失败，预计数量可能不完整。",color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick=vm::confirm){Text("开始读取")}},dismissButton={TextButton(onClick=vm::dismissConfirmation){Text("取消")}})
}
