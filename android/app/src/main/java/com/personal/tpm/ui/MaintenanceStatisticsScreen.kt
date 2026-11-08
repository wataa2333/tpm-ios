package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MaintenanceStatisticsViewModel(private val session: Session) : ViewModel() {
    var report by mutableStateOf(MaintenanceReport(false, null)); private set
    var loading by mutableStateOf(false); private set
    var started by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var task: Job? = null
    private var generation = 0
    private fun reset() {
        generation++; task?.cancel(); loading = false; started = false
        report = MaintenanceReport(false, null); message = "账号、厂区或查询账号已变化，请重新查询"
    }
    init { viewModelScope.launch { session.scope.drop(1).collect { reset() } } }
    init { viewModelScope.launch { session.queryAccounts?.changes?.drop(1)?.collect { reset() } } }
    fun stop() { generation++; task?.cancel(); loading = false; message = "已停止；保留已读完的状态分组，当前结果不完整" }
    fun query(processed: Boolean, span: DateSpan?) {
        task?.cancel()
        val mine = ++generation
        val scope = session.scope.value
        val queryRevision = session.queryAccounts?.revision
        report = MaintenanceReport(processed, if (processed) span else null)
        started = true; loading = true; message = "正在查询保养工单"
        task = viewModelScope.launch {
            fun check() { if (mine != generation || session.scope.value != scope || session.queryAccounts?.revision != queryRevision) throw CancellationException("查询范围已变化") }
            try {
                val result = loadMaintenanceReport(processed, span, checkScope = ::check,
                    progress = { check(); report = it }, reading = { state, count -> check(); message = "${maintenanceStateName(state)} · 已读取 $count 单" },
                    page = { filters, offset, limit -> session.readPage("maintain/pageMaintain", offset, filters, limit) })
                check(); report = result
                message = if (result.complete) "读取完成 · ${result.documents.size} 单" else "结果不完整，仅显示已加载记录"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == generation) message = "读取未完成：${e.message}" }
            finally { if (mine == generation) loading = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceStatisticsScreen(onBack: () -> Unit, openOrder: (String) -> Unit) {
    val session = LocalSession.current
    val vm = viewModel<MaintenanceStatisticsViewModel>(factory = viewModelFactory { initializer { MaintenanceStatisticsViewModel(session) } })
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var days by rememberSaveable { mutableIntStateOf(30) }
    var customSpan by remember { mutableStateOf<DateSpan?>(null) }
    var choosingDate by remember { mutableStateOf(false) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<PersonWorkload?>(null) }
    var focus by remember { mutableIntStateOf(0) }
    var explanation by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now(businessTimeZone().toZoneId())
    val span = customSpan ?: if (days == 0) DateSpan(today.withDayOfMonth(1), today) else DateSpan(today.minusDays(days.toLong() - 1), today)
    fun close() { if (vm.loading) vm.stop(); onBack() }
    DisposableEffect(vm) { onDispose { if (vm.loading) vm.stop() } }
    val report = vm.report
    val series = remember(report, monthly) { report.span?.let { repairTrends(report.documents, it, report.complete, monthly, includeAll = true) }.orEmpty()
        .map { if (it.key == null) it.copy(name = "接单人未明确") else it } }
    val accent = MaterialTheme.colorScheme.primary
    val colors = series.associate { it.key to accent }
    GlassScaffold(contentWindowInsets = appContentInsets(), topBar = { AppTopBar("保养工单统计", onBack = ::close, loading = vm.loading) }) { padding ->
        ScrollableLazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(session.currentFactoryName, style = MaterialTheme.typography.titleLarge)
                SingleChoiceGroup(listOf("保养待处理", "处理统计"), mode, { mode = it }, enabled = !vm.loading)
                Text("按查询账号与当前厂区权限读取；人员按保养接单人分组。", style = MaterialTheme.typography.bodySmall)
                if (mode == 1) {
                    val ranges = listOf(7, 30, 90, 365, 0)
                    SingleChoiceGroup(listOf("近7天", "近30天", "近90天", "近一年", "这个月", "自定义"),
                        if (customSpan != null) 5 else ranges.indexOf(days), { index ->
                            if (index == 5) choosingDate = true else { days = ranges[index]; customSpan = null }
                        }, enabled = !vm.loading, columns = 3)
                    Text("保养结束日期：${span.summary}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassActionButton(onClick = { selectedPerson = null; focus = 0; vm.query(mode == 1, if (mode == 1) span else null) }, enabled = !vm.loading,
                        modifier = Modifier.weight(1f).height(48.dp)) { Text(if (vm.started) "重新查询" else "查询") }
                    if (vm.loading) GlassTextButton(onClick = vm::stop) { Text("停止读取") }
                }
                if (vm.loading) StatisticsProgressIndicator(Modifier.fillMaxWidth())
                AnimatedLabel(vm.message, style = MaterialTheme.typography.bodySmall)
                session.queryAccounts?.fallbackNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (vm.started) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormTitle(if (report.processed) "处理统计 · 按保养结束日期" else "待处理 · 当前状态快照")
                    report.span?.let { Text(it.summary, style = MaterialTheme.typography.bodySmall) }
                    GroupCard {
                        report.states.forEach { state ->
                            val slice = report.slices.firstOrNull { it.state == state }
                            FactRow(maintenanceStateName(state), slice?.let { (if (it.complete) "" else "已加载 ") + it.documents.size } ?: "未读取")
                        }
                        FactRow("去重工单数", report.documents.size.toString())
                    }
                    if (!report.complete || vm.loading) Text("当前结果不完整；未知数量不作零处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (report.duplicated) Text("读取期间工单状态有变化，已去重；请重新查询核对。", style = MaterialTheme.typography.bodySmall)
                    report.slices.filter { !it.complete }.forEach { Text("${maintenanceStateName(it.state)}：${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item { FormTitle("按保养接单人汇总") }
                items(report.people.size) { index ->
                    val person = report.people[index]
                    Surface(Modifier.fillMaxWidth().then(if (AppPreferences.liquidGlass) Modifier.glassSurface(backdrop = null,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), tint = MaterialTheme.colorScheme.surfaceContainerLowest) else Modifier)
                        .clickable { selectedPerson = if (selectedPerson?.key == person.key && selectedPerson != null) null else person },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = if (AppPreferences.liquidGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (person.key == null) "接单人未明确" else person.name, Modifier.weight(1f))
                            Text(if (report.processed) "${person.processed} 单" else "待签到 ${person.sign} · 待完成 ${person.finish}")
                        }
                    }
                }
                selectedPerson?.let { person ->
                    item { FormTitle((if (person.key == null) "接单人未明确" else person.name) + " · 工单明细") }
                    val documents = report.documents.filter { it.personKey == person.key }
                    items(documents.size) { index ->
                        val order = documents[index]
                        GroupCard { GlassTextButton(onClick = { openOrder(order.id) }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(order.title)
                                Text(listOf(order.number, order.asset, order.end).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                }
                if (report.processed && series.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormTitle("保养处理趋势")
                        SingleChoiceGroup(listOf("按天", "按月"), if (monthly) 1 else 0, { monthly = it == 1; focus = 0 })
                        RepairTrendChart(series.take(1), focus, { focus = it }, monthly, colors)
                        }
                    }
                    item { RepairTrendTable(series, focus, { focus = it }, monthly, report.complete, colors) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisclosureToggle(explanation, if (explanation) "收起统计说明" else "统计说明") { explanation = !explanation }
                    Reveal(explanation) { Text("待处理包括待签到、待完成，不限日期。处理统计包括待确认、待复线和已完结，按结束日期筛选。缺少接单人的记录单列，不用受理人或申请人替代。工单明细与处理仍使用当前登录账号权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    AnimatedAppDialog(choosingDate, { choosingDate = false }) {
        fun millis(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = millis(span.days.first), initialSelectedEndDateMillis = millis(span.days.second))
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column {
                DateRangePicker(state, Modifier.heightIn(max = 440.dp), title = { Text("保养结束日期", Modifier.padding(16.dp)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GlassTextButton(onClick = { choosingDate = false }) { Text("取消") }
                    GlassTextButton(onClick = {
                        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        customSpan = DateSpan(day(state.selectedStartDateMillis!!), day(state.selectedEndDateMillis ?: state.selectedStartDateMillis!!)); choosingDate = false
                    }, enabled = state.selectedStartDateMillis != null) { Text("确定") }
                }
            }
        }
    }
}
