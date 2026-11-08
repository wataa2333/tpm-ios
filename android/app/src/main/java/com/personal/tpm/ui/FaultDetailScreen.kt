package com.personal.tpm.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.faultMayHaveRepairNote
import com.personal.tpm.data.faultReturnKind
import com.personal.tpm.data.faultStateLabel
import com.personal.tpm.data.latestRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A repair order, read-only for now (the actions come with the task screens). The order itself shows as soon as it is read; the
 * repairer's note and solution are one more light request after that, and the page does without them if it cannot be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaultDetailScreen(
    id: String, onBack: () -> Unit, onOpenFlow: (String) -> Unit, onOpenTask: (returning: Boolean) -> Unit,
    onEdit: (() -> Unit)? = null, onOpenRepair: () -> Unit = {}, peeking: Boolean = false,
    onReportAsset: ((Jv) -> Unit)? = null, independent: Boolean = false,
) {
    val session = LocalSession.current
    val queryAccounts = LocalQueryAccounts.current
    suspend fun read(path: String, query: Map<String, String> = emptyMap()): Jv =
        if (independent) session.readRequest(path, query) else session.request(path, query)

    val scope = rememberCoroutineScope()
    // The row the order was opened from is on screen at once; the full record replaces it when it arrives.
    var detail by remember { mutableStateOf(OrderPreview.get("Fault", id)) }
    var operationDetail by remember { mutableStateOf<Jv?>(null) }
    var operationNotice by remember { mutableStateOf("") }
    var repair by remember { mutableStateOf<Jv?>(null) }
    var repairLoading by remember { mutableStateOf(false) }
    var repairError by remember { mutableStateOf<String?>(null) }
    var loadGeneration by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var returnLoading by remember(id) { mutableStateOf(true) }
    var returnPerson by remember(id) { mutableStateOf("") }
    var returnError by remember(id) { mutableStateOf(false) }
    var returnRetry by remember(id) { mutableStateOf(0) }
    LaunchedEffect(detail, loading, returnRetry) {
        if (loading) return@LaunchedEffect
        val order = detail ?: return@LaunchedEffect
        if (faultReturnKind(order) != "退单") return@LaunchedEffect
        returnLoading = true; returnPerson = ""; returnError = false
        try {
            val process = order["processInstanceId"].text
            if (process.isNotEmpty()) {
                val history = read("workflow/getWorkflowHistoryNodesByProcessInstanceId", mapOf("processInstanceId" to process)).array
                val actors = com.personal.tpm.data.latestReturnActors(history)
                returnPerson = actors.map { user ->
                    try { read("security/getUserByUserName", mapOf("userName" to user))["realName"].text.ifEmpty { user } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { user }
                }.joinToString("、")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { returnError = true }
        finally { returnLoading = false }
    }

    suspend fun readRepair(generation: Int) {
        if (generation != loadGeneration) return
        repairLoading = true; repairError = null
        try {
            val current = latestRepair(read("repair/getRepairsByFaultId", mapOf("faultId" to id)))
            if (generation == loadGeneration) repair = current
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == loadGeneration) repairError = e.message ?: "维修任务读取失败" }
        finally { if (generation == loadGeneration) repairLoading = false }
    }
    fun load() {
        val generation = ++loadGeneration
        loading = true; error = null
        operationDetail = null; operationNotice = ""
        repair = null; repairError = null; repairLoading = false
        scope.launch {
            try {
                val order = read("fault/getAssetFaultDetailById", mapOf("faultId" to id))
                if (generation != loadGeneration) return@launch
                detail = order
                loading = false
                if (!peeking) {
                    try {
                        val actual = if (!independent || queryAccounts.username.isEmpty()) order else session.request("fault/getAssetFaultDetailById", mapOf("faultId" to id))
                        if (generation != loadGeneration) return@launch
                        if (actual["faultState"].text == order["faultState"].text && actual["processInstanceId"].text == order["processInstanceId"].text) operationDetail = actual
                        else operationNotice = "单据状态已变化，请刷新后操作。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (generation == loadGeneration) operationNotice = "当前登录账号操作权限未通过核对：${e.message ?: "无权限"}" }
                }
                if (faultMayHaveRepairNote(order)) readRepair(generation)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == loadGeneration) error = e.message ?: "加载失败" }
            finally { if (generation == loadGeneration) loading = false }
        }
    }
    // A task of this order was taken forward somewhere (the task screen): read it again.
    val changed by session.changed.collectAsState()
    LaunchedEffect(id, session.accountKey, changed, if (independent) queryAccounts.revision else 0) { load() }

    Box(Modifier.fillMaxSize()) {
    GlassScaffold(contentWindowInsets = appContentInsets(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Looking at an order from a selection is reading only: no buttons to take it forward.
            if (!peeking) Column(Modifier.fillMaxWidth().then(if(onReportAsset!=null) Modifier.navigationBarsPadding() else Modifier)) {
                operationDetail?.let { d -> TaskActionBar(OrderKind.Fault, d, includeNavigationPadding=onReportAsset==null) { returning -> if (!returning && d["taskInfo"]["category"].text == "REPAIRED") onOpenRepair() else onOpenTask(returning) } }
                val asset=detail?.get("asset")
                if(onReportAsset!=null && asset!=null && asset["assetId"].text.isNotBlank() && asset["assetCode"].text.isNotBlank()) {
                    AssetReportButton(onClick={onReportAsset(asset)},enabled=!loading,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp))
                }
            }
        },
        topBar = {
            AppTopBar(
                title = "报修详情", subtitle = detail?.get("sn")?.text?.ifEmpty { null }, onBack = onBack, loading = loading,
                actions = {
                    if (!peeking && onEdit != null && operationDetail?.get("faultState")?.text == "UNTREATED" && operationDetail?.get("taskInfo")?.get("id")?.text.orEmpty().isNotEmpty()) {
                        GlassIconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                    }
                    RefreshButton(spinning = loading) { load() }
                },
            )
        },
    ) { scaffoldPadding ->
        val padding = PaddingValues(top = scaffoldPadding.calculateTopPadding(), bottom = scaffoldPadding.calculateBottomPadding())
        val order = detail
        RoundedSheet(Modifier.padding(padding)) { PageWidth { when {
            order == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedLabel(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    GlassTextButton(onClick = { load() }) { Text("重试") }
                }
            }
            else -> SelectionContainer { Column(
                Modifier.fillMaxSize().fadeInOnShow().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("故障报修工单信息")
                if (operationNotice.isNotEmpty()) AnimatedLabel(operationNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                GroupCard {
                    FactRow("报修名称", order["faultName"].text)
                    FactRow("单号", order["sn"].text)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("状态", Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedLabel(faultStateLabel(order), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = stateColor(order["faultState"].text))
                    }
                    FactRow("故障时间", order["faultTime"].dateText)
                    FactRow("报修资产", order["asset"]["assetName"].text)
                    FactRow("资产编码", order["asset"]["assetCode"].text)
                    FactRow("机台号", order["asset"]["addressCode"].text)
                    FactRow("故障申请人", order["applyUser"].label)
                    FactRow("申请部门", order["applyDept"].label)
                    FactRow("受理部门", order["dept"].label)
                    FactRow("维修受理人", order["user"].label)
                    FactRow("报修人联系电话", order["userPhone"].text)
                    FactRow("维修班组", order["userGroup"].label)
                    FactRow("ERP记账部门", order["division"].label)
                }
                if (order["faultDesc"].text.isNotEmpty()) {
                    SectionTitle("故障描述")
                    GroupCard { Box(Modifier.padding(16.dp)) { SelectionContainer { AnimatedLabel(order["faultDesc"].text, style = MaterialTheme.typography.bodyLarge) } } }
                }
                if (faultMayHaveRepairNote(order)) {
                    SectionTitle("维修任务信息")
                    GroupCard {
                        when {
                            repairLoading -> Box(Modifier.padding(16.dp)) { LoadingText("读取维修任务") }
                            repairError != null -> Column(Modifier.padding(16.dp)) {
                                AnimatedLabel(repairError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                GlassTextButton(onClick = { scope.launch { readRepair(loadGeneration) } }) { Text("重试读取任务") }
                            }
                            repair == null -> FactRow("执行人", "接口未返回维修任务记录")
                            else -> {
                                val task = repair!!
                                FactRow("创建时间", task["createTime"].dateText)
                                FactRow("预计完成时间", task["estimatedCompleteTime"].dateText)
                                FactRow("维修途径", when(task["repairWay"].text) { "SELF" -> "自修"; "OUTSIDE" -> "外协"; else -> task["repairWay"].text })
                                FactRow("维修班组", task["userGroup"].label)
                                FactRow("执行人", task["user"].label.ifBlank { "维修任务未提供执行人" })
                                FactRow("执行人账号", task["user"]["userName"].text.ifBlank { task["user"]["id"].text }.ifBlank { task["user"]["userId"].text })
                                FactRow("签到时间", task["signTime"].dateText.ifBlank { "尚未签到" })
                            }
                        }
                    }
                }
                val kind = faultReturnKind(order)
                val note = repair
                if (kind == "退单") {
                    SectionTitle("维修退单说明")
                    GroupCard {
                        if (returnLoading) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("退单人", Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LoadingText()
                        } else FactRow("退单人", returnPerson.ifEmpty { if (returnError) "获取失败" else "流程未提供退单人" })
                        if (returnError) GlassTextButton(onClick = { returnRetry++ }) { Text("重新获取退单人") }
                        if (note != null) {
                            if (note["signTime"].dateText.isNotEmpty()) {
                                FactRow("签到时间", note["signTime"].dateText)
                            }
                            FactRow("退单原因", note["content"].text.ifEmpty { "未填写" })
                        }
                    }
                } else if (kind == "驳回") {
                    SectionTitle("驳回说明")
                    GroupCard {
                        FactRow("处理人", order["user"]["realName"].text)
                        FactRow("驳回原因", order["ignoreReason"].text.ifEmpty { "未填写" })
                    }
                } else if (note != null && note["content"].text.isNotEmpty()) {
                    SectionTitle("维修解决方案")
                    GroupCard {
                        FactRow("执行人", note["user"]["realName"].text)
                        FactRow("解决方案", note["content"].text)
                    }
                }
                val process = order["processInstanceId"].text
                if (process.isNotEmpty()) GroupCard {
                    ListItem(
                        modifier = Modifier.clickable { onOpenFlow(process) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text("处理流程") },
                        leadingContent = { Icon(Icons.Filled.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
                val photos = order["albums"].array
                if (photos.isNotEmpty()) {
                    SectionTitle("现场照片")
                    GroupCard { photos.forEach { PhotoRow(it) } }
                }
            }
        } } }}
    }
    }
}

@Composable
fun PhotoRow(photo: Jv) {
    val context = LocalContext.current
    val url = photo["url"].text
    Row(
        Modifier.fillMaxWidth().clickable(enabled = url.isNotEmpty()) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        AnimatedLabel(photo["name"].text.ifEmpty { "查看照片" }, color = accentInk())
    }
}
