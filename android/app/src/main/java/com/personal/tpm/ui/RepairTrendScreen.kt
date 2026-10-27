package com.personal.tpm.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*

private val personTrendColors=listOf(Color(0xFF1976D2),Color(0xFF00897B),Color(0xFFEF6C00),Color(0xFF8E24AA),Color(0xFFE53935),Color(0xFF039BE5),Color(0xFF795548),Color(0xFF7CB342))

fun useTrendSplitLayout(width: Float, height: Float): Boolean = width > height

@Composable
fun RepairTrendScreen(vm:RepairPendingViewModel,onBack:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val span=vm.trendSpan ?: vm.selectedSpan
    val days=span?.dayCount() ?: 0
    // Measure the actual window: an Activity density override can leave the resource orientation stale.
    val landscape=useTrendSplitLayout(maxWidth.value,maxHeight.value)
    val sidebarWidth=(maxWidth*.34f).coerceIn(240.dp,320.dp)
    var manualLandscape by rememberSaveable { mutableStateOf(false) }
    val activity=LocalContext.current.findActivity()
    val forceLandscape=days>7 || manualLandscape
    val previousOrientation=rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity,forceLandscape) {
        if(forceLandscape) activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if(forceLandscape) activity?.requestedOrientation=previousOrientation }
    }
    val complete=vm.started && vm.snapshot.complete && !vm.loading
    var monthly by remember(span?.key){mutableStateOf(days>90)}
    val series=remember(vm.snapshot.documents,span,complete,monthly){if(span==null) emptyList() else repairTrends(vm.snapshot.documents,span,complete,monthly=monthly,includeAll=true)}
    var selected by remember{mutableStateOf<Set<String?>>(setOf(ALL_REPAIR_TREND))}
    var focus by remember(span?.key,monthly){mutableIntStateOf(0)}
    var picker by remember{mutableStateOf(false)}
    LaunchedEffect(landscape) { if(landscape) picker=false }
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val accent=MaterialTheme.colorScheme.primary
    val colors=series.mapIndexed{index,item->item.key to if(item.key==ALL_REPAIR_TREND) accent else personTrendColors[(index-1).coerceAtLeast(0)%personTrendColors.size].let{if(dark) androidx.compose.ui.graphics.lerp(it,Color.White,.3f) else it}}.toMap()
    val shown=series.filter{it.key in selected}
    val table=series.filter{it.key==ALL_REPAIR_TREND || it.key in selected}
    val navigationBottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    fun toggle(key:String?){selected=if(key in selected) selected-key else selected+key}
    val controls:@Composable ()->Unit={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("日期范围",style=MaterialTheme.typography.titleSmall)
            RepairDateControls(vm)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(enabled=!vm.loading && vm.selectedSpan!=null,onClick=vm::prepare){AnimatedLabel(if(vm.started) "重新查询" else "查询")}
                if(vm.loading) TextButton(onClick=vm::pause){Text("暂停")}
                else if(vm.started && !vm.showingPrevious && !vm.snapshot.exhausted) TextButton(onClick={vm.load()}){Text("继续")}
            }
            if(vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                AnimatedLabel(if(vm.checking) "正在预估数量" else "已读${vm.snapshot.readCount} / ${vm.expectedTotal?.toString() ?: "未知"}",style=MaterialTheme.typography.bodySmall)
            }
            } }
            GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("统计粒度",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                ChoiceChip(!monthly,{monthly=false},"按天")
                ChoiceChip(monthly,{monthly=true},"按月")
            }
            Text("按故障发现日期统计待确认、待复线及已完结的维修单。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!complete) AnimatedLabel(if(vm.started) "数据未读完整，图表和表格仅为已加载数量。" else "选择日期后查询，再查看完整图表。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            } }
        }
    }
    val pane:@Composable ()->Unit={
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=12.dp,bottom=12.dp+navigationBottom),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(vm.showingPrevious) item { GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("上次数据 · 非最新",style=MaterialTheme.typography.titleSmall,color=accent)
                AnimatedLabel("查询时间：${vm.queriedAt}",style=MaterialTheme.typography.bodySmall)
            } } }
            if(!landscape) {
                item { controls() }
                item { GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("人员对比",style=MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick={picker=true},modifier=Modifier.fillMaxWidth(),border=BorderStroke(1.dp,accent.copy(alpha=.55f))){AnimatedLabel("选择对比人员 · 已选${shown.count{it.key!=ALL_REPAIR_TREND}}人")}
                } } }
            }
            item {
                AnimatedLabel("${span?.summary ?: "请选择日期"} · ${days}天",style=MaterialTheme.typography.titleSmall)
                if(!complete) Text("未读完：空白数据为未知，曲线断开，不按0绘制。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
            if(series.isNotEmpty()) item {
                Crossfade(monthly, animationSpec=tween(Motion.Normal,easing=Motion.Change),label="chart day month") { grain ->
                    val axisSeries=if(grain==monthly) series else if(span==null) emptyList() else repairTrends(vm.snapshot.documents,span,complete,monthly=grain,includeAll=true)
                    val axisDates=axisSeries.firstOrNull()?.dates.orEmpty()
                    RepairTrendChart(axisSeries.filter{it.key in selected},focus.coerceIn(0,axisDates.lastIndex.coerceAtLeast(0)),{focus=it},grain,colors,axisDates)
                }
            }
            else item { GroupCard{Text("选择“全部”或人员后显示曲线",Modifier.padding(16.dp))} }
            item { RepairTrendTable(table,focus,{focus=it},monthly,complete,colors) }
        }
    }
    Scaffold(contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),topBar={AppTopBar("${days}天处理趋势",onBack=onBack,loading=vm.loading,actions={
        if(!landscape) TextButton(onClick={manualLandscape=true}){Text("横屏")}
    })}){padding->
        if(landscape) Row(Modifier.padding(padding).fillMaxSize()){
            LazyColumn(Modifier.width(sidebarWidth).fillMaxHeight(),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=12.dp,bottom=12.dp+navigationBottom),verticalArrangement=Arrangement.spacedBy(10.dp)){
                item{controls()}
                trendPersonItems(series,selected,colors,::toggle,{selected=series.map{it.key}.toSet()},{selected=emptySet()})
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()){pane()}
        } else Box(Modifier.padding(padding)){pane()}
    }

    if(picker) TrendPeopleSheet(series,selected,colors,::toggle,{selected=series.map{it.key}.toSet()},{selected=emptySet()}){picker=false}
    }
}

private fun LazyListScope.trendPersonItems(series:List<RepairTrend>,selected:Set<String?>,colors:Map<String?,Color>,toggle:(String?)->Unit,selectAll:()->Unit,clear:()->Unit) {
    item {
        GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("人员与全部汇总",style=MaterialTheme.typography.titleSmall)
        Row { TextButton(onClick=if(series.all{it.key in selected}) clear else selectAll){AnimatedLabel(if(series.all{it.key in selected}) "取消全选" else "全选")};TextButton(onClick=clear){Text("清空")}}
        } }
    }
    series.firstOrNull{it.key==ALL_REPAIR_TREND}?.let{all->item{TrendPerson(all,all.key in selected,colors[all.key] ?: Color.Gray,{toggle(all.key)},Modifier.fillMaxWidth())}}
    items(series.filter{it.key!=ALL_REPAIR_TREND}.chunked(2),key={"pick:${it.first().key ?: "unknown"}"}){row->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            row.forEach{person->TrendPerson(person,person.key in selected,colors[person.key] ?: Color.Gray,{toggle(person.key)},Modifier.weight(1f))}
            if(row.size==1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrendPerson(person:RepairTrend,selected:Boolean,color:Color,onClick:()->Unit,modifier:Modifier) {
    val fill by animateColorAsState(if(selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f) else MaterialTheme.colorScheme.surfaceContainerLowest,tween(Motion.Normal,easing=Motion.Change),label="trend person fill")
    val outline by animateColorAsState(if(selected) color else MaterialTheme.colorScheme.outlineVariant,tween(Motion.Normal,easing=Motion.Change),label="trend person outline")
    Surface(modifier=modifier.animateContentSize(tween(Motion.Normal,easing=Motion.Change)),shape=RoundedCornerShape(12.dp),color=fill,border=BorderStroke(1.dp,outline)){
        Column(Modifier.clickable(onClick=onClick).padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
            AnimatedLabel((if(selected) "✓ " else "")+person.name,color=color,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            AnimatedLabel(if(person.key==ALL_REPAIR_TREND) "所有人员，含身份未明确" else person.key?.removePrefix("user:")?.removePrefix("id:") ?: "身份未明确",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            AnimatedLabel("${person.counts.sumOf{it ?: 0}}单",style=MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendPeopleSheet(series:List<RepairTrend>,selected:Set<String?>,colors:Map<String?,Color>,toggle:(String?)->Unit,selectAll:()->Unit,clear:()->Unit,onDismiss:()->Unit) {
    AppSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("选择对比人员",style=MaterialTheme.typography.titleMedium);TextButton(onClick=onDismiss){Text("完成")}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){trendPersonItems(series,selected,colors,toggle,selectAll,clear)}
        }
    }
}
