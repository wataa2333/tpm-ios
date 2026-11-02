package com.personal.tpm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*

fun LazyListScope.repairPendingSummary(vm: RepairPendingViewModel, openPeople: () -> Unit, openTrends: () -> Unit) {
    val snapshot=vm.snapshot
    // Pending identities only appear after each document has been checked; unverified list names are not totals.
    val documents=if (vm.processed) snapshot.documents else snapshot.documents.filter { it.key in snapshot.executorChecks }
    val people=vm.displayPeople
    val maximum=people.maxOfOrNull { if (vm.processed) it.processed else it.pending }?.coerceAtLeast(1) ?: 1
    if(vm.showingPrevious) item {
        GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("上次数据 · 非最新",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            AnimatedLabel("查询时间：${vm.queriedAt} · ${vm.previous["range"].text}",style=MaterialTheme.typography.bodySmall)
            if(!vm.previous["complete"].bool) Text("上次读取未完整，请重新查询。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(vm.legacyPrevious) Text("旧版仅保存人员汇总；重新查询一次后可保留完整卡片、工单和趋势。",style=MaterialTheme.typography.bodySmall)
        } }
    }
    item {
        var details by remember { mutableStateOf(false) }
        GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AnimatedLabel(if (vm.processed) "已到待确认 · 人员统计" else "维修待处理",style=MaterialTheme.typography.titleSmall)
            // Updates every 100 ms: do not enqueue text/size transitions for a continuously ticking clock.
            Text("接口查询" + (if(vm.checking) " · 正在获取数量" else " · 实际查询耗时 " + String.format(java.util.Locale.ROOT,"%.1f",vm.elapsedMillis/1000.0) + " 秒"),style=MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
            LocalSession.current.queryAccounts?.fallbackNotice?.let { AnimatedLabel(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            if (vm.processed) {
                RepairDateControls(vm)
                Text("按故障发现日期筛选；统计待确认、待复线及已完结。",style=MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                GlassActionButton(enabled=!vm.loading && (!vm.processed || vm.selectedSpan!=null),onClick=vm::prepare) { AnimatedLabel(if(vm.started) "重新查询" else "查询") }
                if(vm.loading) TextButton(onClick=vm::pause) { Text("暂停") }
                else if(vm.started && !vm.showingPrevious && (!snapshot.exhausted || vm.uncheckedExecutors>0)) TextButton(onClick={vm.load()}) { Text("继续") }
                if(!vm.loading && !vm.showingPrevious && vm.executorFailures>0) TextButton(onClick={vm.load(retryFailures=true)}) { Text("重试失败项") }
            }
            if(vm.checking) { AppProgressIndicator(Modifier.fillMaxWidth()); Text("正在预估工单数量，尚未逐单读取",style=MaterialTheme.typography.bodySmall) }
            if(vm.started && !vm.showingPrevious) {
                val total=vm.expectedTotal
                val checked=snapshot.executorChecks.size
                val workDone=snapshot.readCount.toLong() + if(vm.processed) 0 else checked.toLong()
                val workTotal=total?.let { it * if(vm.processed) 1 else 2 }
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(vm.loading) {
                    if(workTotal!=null) AppProgressIndicator(progress={if(workTotal==0L) 1f else (workDone.toFloat()/workTotal).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                    else AppProgressIndicator(Modifier.fillMaxWidth())
                }
                AnimatedLabel("列表已读 ${snapshot.readCount} / ${total?.toString() ?: "总数未知"}" + if(!vm.processed) " · 详情已核对 $checked / ${vm.detailTotal}" else "",style=MaterialTheme.typography.bodySmall)
                val complete=snapshot.complete && !vm.loading && vm.uncheckedExecutors==0 && vm.executorFailures==0
                val unresolved=if(vm.processed) 0 else documents.count { it.personKey==null }
                AnimatedLabel((if(complete && unresolved==0) "读取完成" else if(complete) "详情读取完成，仍有身份未确认" else "不完整／已加载") + " · 已汇总${documents.size}单",style=MaterialTheme.typography.labelLarge)
                if(!vm.processed) AnimatedLabel("已识别执行人 ${documents.size-unresolved}单 · 身份未明确 $unresolved 单",style=MaterialTheme.typography.bodySmall)
                if(vm.loading && !vm.checking) LoadingText(if(vm.resolving) "逐单核对执行人" else "读取列表")
                }
                DisclosureToggle(details,if(details) "收起加载说明" else "加载说明", compact=true) { details=!details }
                Reveal(details) { Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    AnimatedLabel("查询时间：${vm.queriedAt}",style=MaterialTheme.typography.bodySmall)
                    snapshot.pages.forEach { cursor ->
                        val state=when { cursor.ended && cursor.warnings.isEmpty()->"已全部读取";cursor.ended->"读取未完成";vm.message.startsWith("已暂停")->"已暂停";vm.loading->"正在读取";else->"等待读取" }
                        AnimatedLabel("${repairStateLabel(cursor.state)}：${cursor.offset} / ${cursor.expected?.toString() ?: vm.estimate?.counts?.get(cursor.state)?.toString() ?: "未知"} · $state",style=MaterialTheme.typography.bodySmall)
                        cursor.warnings.forEach { AnimatedLabel(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                    }
                    if(!vm.processed) AnimatedLabel("逐单核对失败${vm.executorFailures}单。待签到和待完成都读取维修任务；未返回可靠账号的记录单独归类。",style=MaterialTheme.typography.bodySmall)
                    if(vm.message.isNotBlank()) AnimatedLabel(vm.message,style=MaterialTheme.typography.bodySmall)
                } }
                if(vm.message.startsWith("读取失败")) AnimatedLabel(vm.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
        } }

    }
    if(!vm.started) return
    item {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        AnimatedLabel(if(vm.processed) "已到待确认 ${people.sumOf { it.processed }}单" else "待签到${people.sumOf { it.sign }} · 待完成${people.sumOf { it.finish }}",style=MaterialTheme.typography.titleSmall)
        if(vm.processed) GlassActionButton(enabled=!vm.checking && !vm.legacyPrevious,onClick=openTrends) {
            Text("统计图与统计表")
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ChoiceChip(!vm.ascending,{vm.sort(false)},"数量降序")
            ChoiceChip(vm.ascending,{vm.sort(true)},"数量升序")
        }
        if(!vm.processed && !vm.legacyPrevious) Text("点击人员查看工单",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(vm.processed) item {
        Card(onClick=openTrends,enabled=!vm.legacyPrevious,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("全部",style=MaterialTheme.typography.titleSmall)
                AnimatedLabel("${people.sumOf { it.processed }}单",style=MaterialTheme.typography.titleMedium)
            }
        }
    }
    items(people.chunked(2),key={"people:${it.first().key ?: "unknown"}"}) { row ->
        val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
        val sign=accentInk()
        val finish=MaterialTheme.colorScheme.primary.copy(alpha=.62f)
        val processed=MaterialTheme.colorScheme.primary
        val track=MaterialTheme.colorScheme.surfaceContainerHighest
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach { person ->
                val first by animateFloatAsState((if(vm.processed) person.processed else person.sign).toFloat()/maximum,tween(Motion.Normal,easing=Motion.Change),label="person workload bar")
                val second by animateFloatAsState(person.finish.toFloat()/maximum,tween(Motion.Normal,easing=Motion.Change),label="person completion bar")
                Card(modifier=Modifier.weight(1f).then(if(vm.processed || vm.legacyPrevious) Modifier else Modifier.clickable{vm.showPerson(person.key);openPeople()}),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        AnimatedLabel(person.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        AnimatedLabel(if(vm.processed) "${person.processed}单" else "${person.pending}单  ›",style=MaterialTheme.typography.titleMedium)
                        AnimatedLabel(person.key?.removePrefix("user:")?.removePrefix("id:") ?: "身份未明确",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                        if(!vm.processed) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            AnimatedLabel("签到${person.sign}",color=sign,style=MaterialTheme.typography.labelSmall)
                            AnimatedLabel("完成${person.finish}",color=sign,style=MaterialTheme.typography.labelSmall)
                        }
                        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                            drawRect(track)
                            drawRect(if(vm.processed) processed else sign,size=Size(size.width*first,size.height))
                            if(!vm.processed) drawRect(finish,topLeft=Offset(size.width*first,0f),size=Size(size.width*second,size.height))
                        }
                    }
                }
            }
            if(row.size==1) Spacer(Modifier.weight(1f))
        }
    }
    if(people.isEmpty()) item { AnimatedLabel(if(vm.loading && !vm.processed) "正在逐单核对，暂未形成汇总" else "本次暂无已汇总记录",style=MaterialTheme.typography.bodySmall) }
    if(vm.processed) item { Text("按故障发现时间faultTime统计待确认、待复线及已完结；图表日期也采用故障发现日期。结果为当前可见单据的状态快照，不是完整绩效。",style=MaterialTheme.typography.bodySmall) }
}

private fun repairStateLabel(state:String)=when(state){"SIGNED"->"待签到";"REPAIRED"->"待完成";"VERIFIED"->"待确认";"RESOLVED"->"待复线";"FINISHED"->"已完结";else->state}

@Composable
fun RepairPersonOrders(vm:RepairPendingViewModel,onBack:()->Unit,openOrder:(String)->Unit) {
    val documents=vm.snapshot.documents.filter { it.key in vm.snapshot.executorChecks && it.personKey==vm.personKey && it.phase in vm.phases }
    val name=vm.snapshot.documents.firstOrNull{it.personKey==vm.personKey}?.personName ?: "人员工单"
    val navigationBottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassScaffold(contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),topBar={AppTopBar("人员维修工单",subtitle=name,onBack=onBack,loading=vm.loading)}) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=16.dp+navigationBottom),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(vm.showingPrevious) item { AnimatedLabel("上次数据 · 非最新 · ${vm.queriedAt}",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall) }
            if(vm.message.startsWith("打开工单失败")) item { AnimatedLabel(vm.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(ManagedPhase.Sign,ManagedPhase.Finish).forEach { phase->ChoiceChip(phase in vm.phases,{vm.togglePhase(phase)},phase.title) }}
                AnimatedLabel("本次已核对匹配"+"${documents.size}单"+if(!vm.snapshot.complete || vm.loading || vm.executorFailures>0 || vm.uncheckedExecutors>0) " · 数据尚不完整" else "",style=MaterialTheme.typography.bodySmall)
                }
            }
            items(documents,key={it.key}) { document->GroupCard {Column(Modifier.fillMaxWidth().clickable{vm.openDocument(document,openOrder)}.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                AnimatedLabel(document.title,style=MaterialTheme.typography.titleSmall)
                AnimatedLabel(listOf(document.number,document.asset,document.phase.title).filter{it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                vm.snapshot.executorChecks[document.key]?.takeIf{it!="已核对"}?.let{AnimatedLabel(it,style=MaterialTheme.typography.bodySmall)}
            }}}
            if(documents.isEmpty()) item{Text("本次已核对记录中暂无匹配工单")}
        }
    }
}
