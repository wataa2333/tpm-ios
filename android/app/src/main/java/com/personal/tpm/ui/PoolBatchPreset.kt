package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import org.json.JSONObject

data class PoolBatchStage(val nodes: List<Jv>, val choices: Map<String, List<String>>, val count: Int = 1,
    val onStage: (JSONObject, Map<String, List<String>>) -> Unit)

@Composable
fun PoolBatchPreset(request: PresetRequest, onClose: () -> Unit, onStart: (BatchPreset) -> Unit) {
    val first = request.items.first()
    val kind = PoolKind.byPath(first.poolPrefix) ?: return
    if (request.action == BatchAction.Requisition) {
        var preset by remember { mutableStateOf(initialBatchErpPreset(request.erp)) }
        var pick by remember { mutableStateOf<PickRequest?>(null) }
        Surface(Modifier.fillMaxSize()) {
            FormPage(title = "保养批量新建领料 · ${request.items.size} 单", busy = false, onClose = onClose, interceptBack = true,
                bottomBar = { GlassBottomSurface {
                    GlassActionButton(onClick = { onStart(preset) }, enabled = preset.isComplete(BatchAction.Requisition),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("为 ${request.items.size} 单新建领料") }
                } }) {
                Text("每张保养单分别创建 ERP 出库单。本操作保留当前保养节点；实发后再关联物料并完成保养。", style = MaterialTheme.typography.bodySmall)
                NewBatchMaterialsSection(request.erp != null, preset, { preset = it }, { pick = it }, target = "保养")
            }
        }
        PickHost(pick) { pick = null }
        return
    }
    if (request.action == BatchAction.Complete && request.operation == BatchOperation.Proceed) {
        PoolExecutionForm(kind, first.id, onClose = onClose, onSubmitFlow = {}, batch = PoolBatchStage(request.nodes, request.selections, count = request.items.size) { data, choices ->
            onStart(BatchPreset(poolTemplate = data.toString(), approverNodes = request.nodes, approvers = choices))
        })
        return
    }
    var group by remember { mutableStateOf(Jv.NULL) }
    var comment by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf(request.selections) }
    var pickingGroup by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<PickRequest?>(null) }
    Surface(Modifier.fillMaxSize()) {
        FormPage(title = "${kind.title} · ${request.items.size} 单", busy = false, onClose = onClose, interceptBack = true,
            bottomBar = { GlassBottomSurface {
                GlassActionButton(onClick = { onStart(BatchPreset(group = group, comment = comment, approverNodes = request.nodes, approvers = choices)) },
                    enabled = if (request.operation == BatchOperation.Reject) comment.isNotBlank() else missingAssigneeMessage(request.nodes, choices) == null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Text(if (request.operation == BatchOperation.Reject) request.action.rejectTitle else request.action.title)
                }
            } }) {
            if (request.operation == BatchOperation.Reject) OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth(), label = { Text("退回／驳回原因 *") })
            else {
                if (request.action == BatchAction.Accept) GroupCard { PickRow("保养班组（留空沿用各单）", group.label, false) { pickingGroup = true } }
                Text("所选 ${request.items.size} 单逐单核验权限与状态，失效或标准不符的单据会报告失败。")
                GroupCard { AssigneeRows(request.nodes, choices, null, { key, change -> choices = choices + (key to change(choices[key].orEmpty())) }, { pick = it }) }
            }
        }
    }
    if (pickingGroup) PoolGroupPicker(onPick = { group = it }, onClose = { pickingGroup = false })
    PickHost(pick) { pick = null }
}
