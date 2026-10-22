package com.personal.tpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.approvalStateName
import com.personal.tpm.data.materialQuantity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** An ERP outbound order, read-only for now: the order, its materials with the applied and issued quantities, the process. */
@Composable
fun ErpDetailScreen(id: String, onBack: () -> Unit, onOpenFlow: (String) -> Unit, onOpenTask: (returning: Boolean) -> Unit, onEdit: (() -> Unit)? = null, peeking: Boolean = false) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf(OrderPreview.get("Erp", id)) }
    var lines by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val (order, materials) = coroutineScope {
                    val o = async { session.request("erpOutbound/getErpOutboundById", mapOf("outboundId" to id)) }
                    val m = async { session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to id)).array }
                    o.await() to m.await()
                }
                detail = order; lines = materials
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }
    val changed by session.changed.collectAsState()
    LaunchedEffect(id, changed) { load() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (!peeking) detail?.let { TaskActionBar(com.personal.tpm.data.OrderKind.Erp, it, onOpen=onOpenTask) } },
        topBar = {
            AppTopBar(
                title = "出库详情", subtitle = detail?.get("outNumber")?.text?.ifEmpty { null }, onBack = onBack, loading = loading,
                actions = {
                    if (!peeking && onEdit != null && detail?.get("processInstanceId")?.text.orEmpty().isEmpty() && detail?.get("createBy")?.text == session.accountName) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                    }
                    RefreshButton(spinning = loading) { load() }
                },
            )
        },
    ) { scaffoldPadding ->
        val order = detail
        Box(Modifier.padding(top = scaffoldPadding.calculateTopPadding(), bottom = scaffoldPadding.calculateBottomPadding())) {
            RoundedSheet {
                PageWidth {
                    if (order == null) Box(Modifier.fillMaxSize(), Alignment.Center) {
                        if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                            TextButton(onClick = { load() }) { Text("重试") }
                        }
                    } else SelectionContainer { Column(Modifier.fillMaxSize().fadeInOnShow().elastic().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("ERP出库详情")
                        GroupCard {
                            FactRow("出库单号", order["outNumber"].text)
                            FactRow("单号", order["sn"].text)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("状态", Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(approvalStateName(order["approvalState"].text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = approvalColor(order["approvalState"].text))
                            }
                            FactRow("申请人", order["user"]["realName"].text)
                            FactRow("申请日期", order["applyDate"].dateText)
                            FactRow("项目号", order["projectCode"].text)
                            FactRow("项目名称", order["projectDesc"].text)
                            FactRow("关联单号", order["referenceSn"].text)
                            FactRow("领用人", order["recipientUser"]["realName"].text)
                            FactRow("领用部门", order["dept"]["deptName"].text)
                            FactRow("ERP记账部门", order["division"]["divisionDesc"].text)
                            FactRow("出库时间", order["outboundTime"].dateText)
                        }
                        SectionTitle("出库物料 · ${lines.size} 项")
                        GroupCard {
                            lines.forEachIndexed { index, line ->
                                val material = line["material"]
                                FactRow("物料", material["materialDesc"].text)
                                FactRow("物料编码", material["materialCode"].text)
                                FactRow("批次", material["lot"].text)
                                FactRow("子库", material["subInv"].text)
                                FactRow("货位", material["locatorCode"].text)
                                FactRow("申请数量", materialQuantity(line["entryQuantity"].text, material))
                                FactRow("实发数量", materialQuantity(line["actualQuantity"].text, material))
                                if (index < lines.lastIndex) GroupDivider()
                            }
                            if (lines.isEmpty()) Text("没有出库物料", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (order["remark"].text.isNotEmpty()) {
                            SectionTitle("用途")
                            GroupCard { Box(Modifier.padding(16.dp)) { SelectionContainer { Text(order["remark"].text) } } }
                        }
                        val process = order["processInstanceId"].text
                        if (process.isNotEmpty()) GroupCard {
                            ListItem(
                                modifier = Modifier.clickable { onOpenFlow(process) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text("处理流程") },
                                leadingContent = { Icon(Icons.Filled.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            )
                        }
                    } }
                }
            }
        }
    }
    }
}
