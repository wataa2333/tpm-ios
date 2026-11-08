package com.personal.tpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.personal.tpm.data.*

private fun Modifier.tableEdgeFade(scroll: ScrollState, color: Color) = clipToBounds().drawWithContent {
    drawContent()
    val edge = 14.dp.toPx().coerceAtMost(size.width / 4)
    if(scroll.value > 0) drawRect(Brush.horizontalGradient(listOf(color,Color.Transparent),0f,edge),size=Size(edge,size.height))
    if(scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent,color),size.width-edge,size.width),topLeft=Offset(size.width-edge,0f),size=Size(edge,size.height))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepairTrendTable(series:List<RepairTrend>,focus:Int,onFocus:(Int)->Unit,monthly:Boolean,complete:Boolean,colors:Map<String?,Color>) {
    if(series.isEmpty()) return
    var transposed by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AnimatedLabel("统计表 · ${if(monthly) "按月" else "按天"}",style=MaterialTheme.typography.titleSmall)
            SingleChoiceGroup(listOf("日期为行", "人员为行"), if(transposed) 1 else 0, { transposed = it == 1 })
            if(!complete) Text("已加载数量，—表示未知",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(targetState=transposed,transitionSpec={
                (fadeIn(tween(Motion.Normal,easing=Motion.Change)) togetherWith fadeOut(tween(Motion.Fast,easing=Motion.Change))).using(SizeTransform(clip=false))
            },label="table axes") { swapped ->
                TrendTableGrid(series,focus,onFocus,monthly,complete,colors,swapped)
            }
        }
    }
}

@Composable
private fun TrendTableGrid(series:List<RepairTrend>,focus:Int,onFocus:(Int)->Unit,monthly:Boolean,complete:Boolean,colors:Map<String?,Color>,swapped:Boolean) {
    val dates=series.first().dates
    val all=series.firstOrNull{it.key==ALL_REPAIR_TREND}
    val people=series.filter{it.key!=ALL_REPAIR_TREND}
    val horizontal=rememberScrollState()
    val vertical=rememberLazyListState()
    LaunchedEffect(focus,dates,swapped) { if(!swapped && focus in dates.indices) vertical.animateScrollToItem(focus) }
    val labelWidth=if(swapped) 100.dp else if(monthly) 86.dp else 100.dp
    val totalWidth=60.dp
    val cellWidth=100.dp
    val cellPixels=with(LocalDensity.current) { cellWidth.roundToPx() }
    LaunchedEffect(focus,swapped,horizontal.viewportSize,horizontal.maxValue) {
        if(swapped && focus in dates.indices && horizontal.viewportSize>0) {
            val left=focus*cellPixels
            val right=left+cellPixels
            val target=when {
                left<horizontal.value -> left
                right>horizontal.value+horizontal.viewportSize -> right-horizontal.viewportSize
                else -> horizontal.value
            }
            horizontal.animateScrollTo(target.coerceIn(0,horizontal.maxValue))
        }
    }
    val columns=if(swapped) dates.size else people.size
    val base=MaterialTheme.colorScheme.surfaceContainerLowest
    val accent=MaterialTheme.colorScheme.primary
    fun count(value:Int?)=value?.toString() ?: "—"
    fun total(values:List<Int?>)=if(values.any{it!=null}) values.sumOf{it ?: 0}.toString() else "—"
    @Composable fun Label(text:String,account:String?=null,color:Color=MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.width(labelWidth).padding(end=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            AnimatedLabel(text,color=color,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(account!=null) AnimatedLabel(account,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
    @Composable fun Total(text:String) { AnimatedLabel(text,Modifier.width(totalWidth).padding(end=6.dp),color=accent,style=MaterialTheme.typography.labelMedium,maxLines=1) }
    @Composable fun RowScope.Cells(background:Color=base,content:@Composable RowScope.()->Unit) {
        Box(Modifier.weight(1f).tableEdgeFade(horizontal,background)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(horizontal)) { Row(Modifier.width(cellWidth*columns),content=content) }
        }
    }
    @Composable fun Value(text:String,index:Int=-1) { AnimatedLabel(text,Modifier.width(cellWidth).then(if(swapped && index==focus) Modifier.background(MaterialTheme.colorScheme.primaryContainer).clickable{onFocus(index)} else if(swapped && index>=0) Modifier.clickable{onFocus(index)} else Modifier).padding(horizontal=8.dp,vertical=6.dp),color=if(swapped && index==focus) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodySmall,maxLines=1) }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Label(if(swapped) "人员" else "日期")
            Total(if(swapped) "合计" else "全部")
            Cells {
                if(swapped) dates.forEachIndexed { index,date ->
                    Box(Modifier.width(cellWidth).then(if(index==focus) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier).clickable{onFocus(index)}.padding(horizontal=8.dp,vertical=6.dp)) {
                        AnimatedLabel(trendDateLabel(date,monthly),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                } else people.forEach { person ->
                    Column(Modifier.width(cellWidth).padding(horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        AnimatedLabel(person.name,color=colors[person.key] ?: accent,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        AnimatedLabel(person.key?.removePrefix("user:")?.removePrefix("id:") ?: "身份未明确",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider()
        if(swapped) {
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Label("全部",color=accent)
                Total(all?.let{total(it.counts)} ?: "—")
                Cells { dates.indices.forEach { Value(count(all?.counts?.get(it)),it) } }
            }
            HorizontalDivider()
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=280.dp),state=vertical) {
            val rowCount=if(swapped) people.size else dates.size
            itemsIndexed((0 until rowCount).toList(),key={_,index->if(swapped) people[index].key ?: "unknown" else dates[index].toString()}) { _,index ->
                val background=if(!swapped && index==focus) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.3f) else base
                Surface(color=background) {
                    Row(Modifier.fillMaxWidth().then(if(swapped) Modifier else Modifier.clickable{onFocus(index)}).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(swapped) Label(people[index].name,people[index].key?.removePrefix("user:")?.removePrefix("id:") ?: "身份未明确",colors[people[index].key] ?: accent)
                        else Label(trendDateLabel(dates[index],monthly))
                        Total(if(swapped) total(people[index].counts) else count(all?.counts?.get(index)))
                        Cells(background) {
                            if(swapped) people[index].counts.forEachIndexed{dateIndex,value->Value(count(value),dateIndex)}
                            else people.forEach{Value(count(it.counts[index]))}
                        }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            }
        }
        if(!swapped) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Label(if(complete) "合计" else "已加载合计")
                Total(all?.let{total(it.counts)} ?: "—")
                Cells { people.forEach{Value(total(it.counts))} }
            }
        }
    }
}
