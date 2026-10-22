package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.activityTypeText
import com.personal.tpm.data.commentTypeText
import com.personal.tpm.data.countersignNames
import com.personal.tpm.data.historyAssigneeNames
import com.personal.tpm.data.historyDuration
import com.personal.tpm.data.historyTimePrefix
import com.personal.tpm.data.historyTimeText
import com.personal.tpm.data.historyUserNames
import com.personal.tpm.data.isCountersignHistory
import com.personal.tpm.data.orderedHistory
import com.personal.tpm.data.relativeTimeText
import com.personal.tpm.data.showsConditionTag
import com.personal.tpm.data.workflowParticipantLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun typeColor(type: String): Color = when (type) {
    "transfer" -> Color(0xFFF57C00); "delegate" -> Color(0xFF8E24AA); "delegateResolve" -> Color(0xFF00897B)
    "startEvent" -> Color(0xFF00ACC1); "endEvent" -> Color(0xFFD32F2F); "userTask" -> Color(0xFF2E7D32); else -> Color(0xFF757575)
}

private fun conditionColor(condition: String): Color = when (condition) {
    "流程发起" -> Color(0xFF00ACC1); "流程结束" -> Color(0xFFF57C00); "提交", "通过", "同意" -> Color(0xFF2E7D32); "拒绝", "驳回" -> Color(0xFFD32F2F)
    else -> Color(0xFF757575)
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = color,
    )
}

/**
 * The process of an order: what each step did and who did it, newest last, then the steps the process defines. The history and the
 * definition are asked together; the real names of the people come in as they are found (until then their user names show).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkflowScreen(processId: String, onBack: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var definition by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val names = remember { mutableStateMapOf<String, String>() }
    fun nameOf(user: String) = names[user]?.ifEmpty { null } ?: user

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val (nodes, steps) = coroutineScope {
                    val h = async { session.request("workflow/getWorkflowHistoryNodesByProcessInstanceId", mapOf("processInstanceId" to processId)).array }
                    val d = async { session.request("workflow/getAssigneeElementsByProcessInstanceId", mapOf("processInstanceId" to processId)).array }
                    h.await() to d.await()
                }
                history = orderedHistory(nodes); definition = steps
                loading = false
                // Real names, one request per person not known yet, all at once.
                val missing = historyUserNames(history).filter { names[it] == null }
                coroutineScope {
                    missing.map { user ->
                        async {
                            try { names[user] = session.request("security/getUserByUserName", mapOf("userName" to user))["realName"].text }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { /* the user name stays */ }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }
    LaunchedEffect(processId) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "处理流程", onBack = onBack, loading = loading,
                actions = { RefreshButton(spinning = loading) { load() } },
            )
        },
    ) { scaffoldPadding ->
        Box(Modifier.padding(top = scaffoldPadding.calculateTopPadding())) {
            RoundedSheet {
                PageWidth {
                    SelectionContainer { Column(Modifier.fillMaxSize().fadeInOnShow().elastic().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        error?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { load() }) { Text("重试") }
                        }
                        if (history.isNotEmpty()) {
                            SectionTitle("处理记录")
                            GroupCard {
                                history.forEachIndexed { index, node ->
                                    HistoryNode(node, ::nameOf)
                                    if (index < history.lastIndex) GroupDivider()
                                }
                            }
                        }
                        if (definition.isNotEmpty()) {
                            SectionTitle("流程节点")
                            GroupCard {
                                definition.forEachIndexed { index, node ->
                                    val records = history.filter { it["activityId"].text == node["taskKey"].text }
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(node["taskName"].text, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (records.isEmpty()) "尚无处理记录" else if (records.any { it["endTime"].isNull }) "当前节点" else "已有处理记录",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        workflowParticipantLabel(node["assignees"].text).takeIf { it.isNotEmpty() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (index < definition.lastIndex) GroupDivider()
                                }
                            }
                        }
                        if (!loading && history.isEmpty() && definition.isEmpty() && error == null) EmptyState("暂无流程记录")
                    } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryNode(node: Jv, nameOf: (String) -> String) {
    val type = node["activityType"].text
    val time = historyTimeText(node)
    val assignees = historyAssigneeNames(node)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(typeColor(type), CircleShape))
                Text("${historyTimePrefix(node)}：${time.ifEmpty { "等待处理" }}", style = MaterialTheme.typography.titleSmall)
                if (time.isNotEmpty()) Text("（${relativeTimeText(time)}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Person, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (assignees.isEmpty()) "无" else assignees.joinToString("、", transform = nameOf), style = MaterialTheme.typography.bodyMedium)
                }
                if (showsConditionTag(node)) Chip(node["condition"].text, conditionColor(node["condition"].text))
                Chip(node["activityName"].text.ifEmpty { activityTypeText(type) }, typeColor(type))
            }
            if (isCountersignHistory(node)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("会签", if (node["countersignPending"].number > 0) Color(0xFFF57C00) else Color(0xFF2E7D32))
                    if (node["countersignStatusText"].text.isNotEmpty()) Text(node["countersignStatusText"].text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                listOf("会签人员" to countersignNames(node, "all"), "已处理" to countersignNames(node, "completed"), "待处理" to countersignNames(node, "pending")).forEach { (label, list) ->
                    if (list.isNotEmpty()) Text("$label：${list.joinToString("、", transform = nameOf)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val comments = node["comments"].array
            comments.forEach { comment ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Chip(commentTypeText(comment), when (comment["type"].text) { "transfer" -> Color(0xFFF57C00); "delegate" -> Color(0xFF8E24AA); "delegateResolve" -> Color(0xFF00897B); else -> MaterialTheme.colorScheme.primary })
                    Text(comment["fullMessage"].text.ifEmpty { comment["message"].text }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (comments.isEmpty() && type == "userTask" && !node["endTime"].isNull) Text("意见说明：无", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node["durationInMillis"].number > 0) Text("耗时：" + historyDuration(node["durationInMillis"].number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
