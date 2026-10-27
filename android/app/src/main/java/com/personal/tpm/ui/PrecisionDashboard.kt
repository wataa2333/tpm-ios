package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.tpm.data.*

@Composable
fun PrecisionDashboard(onBack: () -> Unit, openAsset: (String) -> Unit) {
    val owner = LocalSession.current
    val queries = LocalQueryAccounts.current
    val scope by owner.scope.collectAsState()
    val vm = viewModel<PrecisionDashboardModel>(factory = viewModelFactory { initializer { PrecisionDashboardModel(owner, queries) } })
    LaunchedEffect(scope, queries.revision) { vm.ensureLoaded(scope, queries.revision) }
    val colors = listOf(Color(0xFF167D8D), Color(0xFF4388C7), Color(0xFF8B86AF), Color(0xFFC68A30), Color(0xFFBC5360))
    val matching = remember(vm.snapshot, vm.search, vm.grade) { vm.matching() }
    Scaffold(topBar = { AppTopBar("精密度检查看板", onBack = onBack, loading = vm.loading) }) { padding ->
        PageWidth {
            ScrollableLazyColumn(Modifier.padding(padding).fillMaxSize().elastic(), state = vm.listState,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    PrecisionPanel {
                        Text("017CNC加工中心", style = MaterialTheme.typography.titleLarge)
                        Text(owner.currentFactoryName)
                        Text("查询：${queries.label(owner)} · 登录：${owner.accountName}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(vm.readAt.takeIf { it.isNotEmpty() }?.let { "更新：$it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !vm.loading, onClick = vm::refresh) { Text(if (vm.loading) "更新中…" else "刷新数据") }
                        }
                        if (vm.error.isNotEmpty()) Text(vm.error, color = MaterialTheme.colorScheme.error)
                    }
                }
                vm.snapshot?.let { data ->
                    item {
                        PrecisionPanel {
                            Text("检测进度", style = MaterialTheme.typography.titleMedium)
                            Text("已检：${data.rows.size}台", style = MaterialTheme.typography.headlineSmall)
                            val total = data.total
                            if (total != null && total > 0) {
                                Text("总数：${total}台 · 未检：${total - data.rows.size}台")
                                LinearProgressIndicator(progress = { data.rows.size.toFloat() / total }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                                Text("覆盖率：${percent(data.rows.size, total)}")
                            }
                            if (data.totalNotice.isNotEmpty()) Text(data.totalNotice, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item {
                        PrecisionPanel {
                            Text("精度等级分布", style = MaterialTheme.typography.titleMedium)
                            GradeBar(data.grades(), colors, data.total?.minus(data.rows.size) ?: 0)
                            FlowLegend(data.grades(), colors, data.total?.minus(data.rows.size))
                        }
                    }
                    item { SectionTitle("各品牌精度等级") }
                    data.brands.forEach { (brand, rows) ->
                        item(key = "brand-$brand") {
                            PrecisionPanel {
                                val total = data.brandTotals?.get(brand)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(brand, style = MaterialTheme.typography.titleMedium)
                                    if (total != null) Text("共${total}台", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (total != null && total > 0) {
                                    Text("已检：${rows.size}台（${percent(rows.size, total)}）\n未检：${total - rows.size}台（${percent(total - rows.size, total)}）", style = MaterialTheme.typography.bodySmall)
                                } else Text("设备总数暂不可用", style = MaterialTheme.typography.bodySmall)
                                GradeBar(data.grades(rows), colors, total?.minus(rows.size) ?: 0)
                                FlowLegend(data.grades(rows), colors)
                            }
                        }
                    }
                    item {
                        PrecisionPanel {
                            Text("检测日期分布", style = MaterialTheme.typography.titleMedium)
                            PrecisionDateChart(data.rows, vm.days) { vm.days = it }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("设备检测明细")
                            SearchField(vm.search, { vm.search = it; vm.shown = 20 }, "搜索机台号、编码、名称或等级", {})
                            GradeFilters(vm.grade) { vm.grade = it; vm.shown = 20 }
                            Text("共${matching.size}台 · 已显示${minOf(vm.shown, matching.size)}台", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    matching.take(vm.shown).forEach { row ->
                        val asset = row["asset"]
                        val id = asset["assetId"].text
                        item(key = "asset-$id") {
                            LaunchedEffect(id, vm.loading) { vm.loadPeople(row) }
                            Card(onClick = { openAsset(id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(asset["addressCode"].text.ifEmpty { asset["assetCode"].text }, fontWeight = FontWeight.Light, style = MaterialTheme.typography.titleLarge)
                                        Text(asset["assetName"].text, fontWeight = FontWeight.Light, style = MaterialTheme.typography.bodyMedium)
                                        Text(asset["assetCode"].text, fontWeight = FontWeight.Light, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("检测日期：${row["analysisDate"].text}", fontWeight = FontWeight.Light, style = MaterialTheme.typography.bodySmall)
                                        Text(vm.people[id] ?: "工单检测人：读取中…", fontWeight = FontWeight.Light, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(row["analysisResult"].text.removePrefix("RESULT_"), fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Light,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), maxLines = 1, softWrap = false,
                                        modifier = Modifier.widthIn(min = 80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                    }
                    if (vm.shown < matching.size) item {
                        SoftButton(onClick = { vm.shown += 20 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多（20台）") }
                    }
                }
            }
        }
    }
}

private fun percent(value: Int, total: Int) = String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0 / total)

@Composable
internal fun PrecisionPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun GradeBar(counts: List<Int>, colors: List<Color>, missing: Int = 0) {
    Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        counts.forEachIndexed { index, count -> if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(colors[index])) }
        if (missing > 0) Box(Modifier.weight(missing.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowLegend(counts: List<Int>, colors: List<Color>, missing: Int? = null) {
    val missingColor = MaterialTheme.colorScheme.outlineVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        precisionGrades.forEachIndexed { i, grade ->
            Text(buildAnnotatedString { withStyle(SpanStyle(color = colors[i])) { append("● ") }; append("$grade：${counts[i]}") }, style = MaterialTheme.typography.bodySmall)
        }
        if (missing != null) Text(buildAnnotatedString { withStyle(SpanStyle(color = missingColor)) { append("● ") }; append("未检：$missing") }, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeFilters(selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip(selected == null, { onSelect(null) }, "全部")
        precisionGrades.forEach { grade -> ChoiceChip(selected == grade, { onSelect(if (selected == grade) null else grade) }, grade) }
    }
}
