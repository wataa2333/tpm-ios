package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.ApiException
import com.personal.tpm.data.precisionAnalysisRequest
import com.personal.tpm.data.precisionCncRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Manual, read-only probe using the app's own session, host and factory. Never uses Chrome credentials. */
@Composable
internal fun PrecisionApiProbe() {
    val session = LocalSession.current
    val queries = LocalQueryAccounts.current
    val revision = queries.revision
    val scope by session.scope.collectAsState()
    val tasks = rememberCoroutineScope()
    var job by remember(scope, revision) { mutableStateOf<Job?>(null) }
    var busy by remember(scope, revision) { mutableStateOf(false) }
    var result by remember(scope, revision) { mutableStateOf("") }
    var failed by remember(scope, revision) { mutableStateOf(false) }
    DisposableEffect(scope, revision) {
        onDispose { job?.cancel() }
    }
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前账号：${session.accountName} · ${session.currentFactoryName}")
            Text("查询账号：${queries.username.ifEmpty { session.accountName }}")
            Text("使用已配置的查询账号，只显示017CNC加工中心。不会自动查询或修改单据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SoftButton(enabled = !busy && session.signedIn && session.currentFactoryId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    result = ""
                    failed = false
                    val requestedScope = session.scope.value
                    job = tasks.launch {
                        try {
                            // Same read-only POST and defaults captured from the web dashboard.
                            // Category ids vary by factory: filter the returned assets by their category fields.
                            val source = queries.source(session)
                            val response = source.request("precise/getPreciseAnalysis", body = precisionAnalysisRequest())
                            if (session.scope.value != requestedScope || queries.revision != revision) throw CancellationException("scope changed")
                            val cnc = precisionCncRows(response)
                            val groups = response.array
                            val all = groups.flatMap { it["preciseAnalysisList"].array }
                            result = buildString {
                                append("接口成功返回 JSON。\n分组：${groups.size}；返回分析项：${all.size}；017CNC分析项：${cnc.size}。")
                                append("\n在用；资产状态：正常／报警／故障。")
                                if (cnc.isEmpty()) append("\n没有匹配数据；只能确认接口有响应，不能确认该账号能看到目标设备。")
                                else {
                                    append("\n样例（最多3台）：")
                                    cnc.take(3).forEach { row ->
                                        val asset = row["asset"]
                                        append("\n${asset["assetCode"].text} · ${asset["brand"]["brandName"].text.ifEmpty { "未知品牌" }}")
                                        append(" · ${row["analysisResult"].text.removePrefix("RESULT_")} · ${row["analysisDate"].text}")
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (session.scope.value == requestedScope && queries.revision == revision) {
                                failed = true
                                result = when ((e as? ApiException)?.status) {
                                    401 -> "登录已失效，请重新登录后验证。"
                                    403 -> "当前查询账号没有精密度分析查询权限（403）。"
                                    else -> "验证失败：${e.message ?: "未知错误"}"
                                }
                            }
                        } finally {
                            if (session.scope.value == requestedScope && queries.revision == revision) busy = false
                        }
                    }
                }) { Text(if (busy) "正在验证…" else "验证精密度接口") }
            if (result.isNotEmpty()) Text(result,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
