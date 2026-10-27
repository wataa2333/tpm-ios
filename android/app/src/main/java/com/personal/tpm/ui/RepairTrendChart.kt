package com.personal.tpm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.abs

private data class MovingTrend(val item: RepairTrend, val from: List<Int?>)

fun trendDateLabel(date:LocalDate,monthly:Boolean):String=if(monthly) date.format(DateTimeFormatter.ofPattern("yyyy-MM")) else "${date.format(DateTimeFormatter.ofPattern("MM-dd"))} 周${"一二三四五六日"[date.dayOfWeek.value-1]}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepairTrendChart(series:List<RepairTrend>,focus:Int,onFocus:(Int)->Unit,monthly:Boolean,colors:Map<String?,Color>,dates:List<LocalDate> = series.firstOrNull()?.dates.orEmpty()) {
    if(dates.isEmpty()) return
    val pointCount=dates.size
    val autoWindow=minOf(pointCount,if(monthly) 12 else if(pointCount<=7) 7 else 14).toFloat()
    var window by remember(dates,monthly){mutableFloatStateOf(autoWindow)}
    var start by remember(dates,monthly){mutableFloatStateOf(0f)}
    var yZoom by remember(dates,monthly){mutableFloatStateOf(1f)}
    val visible=window.roundToInt().coerceIn(1,pointCount)
    val first=start.roundToInt().coerceIn(0,pointCount-visible)
    val last=first+visible-1
    LaunchedEffect(focus){if(focus !in first..last) start=(focus-visible/2).coerceIn(0,pointCount-visible).toFloat()}
    val highest=series.maxOfOrNull{it.counts.subList(first,last+1).filterNotNull().maxOrNull() ?: 0}?.coerceAtLeast(1) ?: 1
    val desiredCeiling=ceil(highest.toDouble()/yZoom).toInt().coerceAtLeast(1)
    val step=ceil(desiredCeiling/4.0).toInt().coerceAtLeast(1)
    val ceiling=if(desiredCeiling<=4) desiredCeiling else step*4
    val animatedCeiling by animateFloatAsState(ceiling.toFloat(), tween(Motion.Normal, easing=Motion.Change), label="chart vertical scale")
    val animatedFirst by animateFloatAsState(first.toFloat(), tween(Motion.Normal, easing=Motion.Change), label="chart horizontal position")
    val animatedWindow by animateFloatAsState(visible.toFloat(), tween(Motion.Normal, easing=Motion.Change), label="chart horizontal scale")
    val retained=remember(dates){mutableStateMapOf<String?,MovingTrend>()}
    val blend=remember(dates){Animatable(1f)}
    val selectedKeys=series.map{it.key}.toSet()
    LaunchedEffect(series) {
        series.forEach { item ->
            val previous=retained[item.key]
            val from=previous?.item?.counts?.mapIndexed { index, value ->
                val old=previous.from.getOrNull(index)
                if(value==null || old==null) value else (old+(value-old)*blend.value).roundToInt()
            } ?: item.counts
            retained[item.key]=MovingTrend(item,from)
        }
        blend.snapTo(0f)
        blend.animateTo(1f,tween(Motion.Slow,easing=Motion.Change))
        delay(Motion.Fast.toLong())
        retained.keys.toList().filter{it !in selectedKeys}.forEach{retained.remove(it)}
    }
    val lineAlpha=retained.keys.toList().associateWith { id ->
        key(id) {
            val alpha=remember{Animatable(0f)}
            LaunchedEffect(id in selectedKeys){alpha.animateTo(if(id in selectedKeys) 1f else 0f,tween(Motion.Fast,easing=Motion.Change))}
            alpha.value
        }
    }
    val measurer=rememberTextMeasurer()
    val labelStyle=MaterialTheme.typography.labelSmall.copy(color=MaterialTheme.colorScheme.onSurfaceVariant)
    val grid=MaterialTheme.colorScheme.outlineVariant
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    GroupCard{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        AnimatedLabel(if(monthly) "每月工单数量" else "每日工单数量",style=MaterialTheme.typography.titleSmall)
        Text("上下滑动页面，横向拖动看日期，双指缩放",style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(240.dp)
            .pointerInput(first,visible,pointCount){detectTapGestures{p->
                val left=46.dp.toPx();val right=size.width-28.dp.toPx()
                val index=if(visible==1) first else first+((p.x-left)/(right-left)*(visible-1)).roundToInt().coerceIn(0,visible-1)
                onFocus(index)
            }}
            .pointerInput(dates,monthly){awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                var claimed=false
                var accumulated=Offset.Zero
                do {
                    val event=awaitPointerEvent()
                    if(!claimed && event.changes.any{it.isConsumed}) break
                    val fingers=event.changes.count{it.pressed}
                    val pan=event.calculatePan()
                    accumulated+=pan
                    if(!claimed) {
                        when(chartGestureIntent(fingers,accumulated.x,accumulated.y,viewConfiguration.touchSlop)) {
                            ChartGestureIntent.Chart -> claimed=true
                            ChartGestureIntent.Page -> break
                            ChartGestureIntent.Undecided -> Unit
                        }
                    }
                    if(claimed && fingers>0) {
                        val centroid=event.calculateCentroid()
                        val zoom=if(fingers>=2) event.calculateZoom() else 1f
                        val current=window.roundToInt().coerceIn(1,pointCount)
                        val width=(size.width-74.dp.toPx()).coerceAtLeast(1f)
                        val fraction=((centroid.x-46.dp.toPx())/width).coerceIn(0f,1f)
                        val anchor=start+fraction*(current-1)
                        window=(window/zoom).coerceIn(minOf(3,pointCount).toFloat(),pointCount.toFloat())
                        start=(anchor-fraction*(window.roundToInt()-1)-pan.x/width*(current-1).coerceAtLeast(1)).coerceIn(0f,(pointCount-window.roundToInt()).coerceAtLeast(0).toFloat())
                        if(zoom!=1f) { yZoom=(yZoom*zoom).coerceIn(.25f,8f) }
                        event.changes.forEach{if(it.position!=it.previousPosition) it.consume()}
                    }
                } while(event.changes.any{it.pressed})
            }}) {
            val left=46.dp.toPx();val right=size.width-28.dp.toPx();val top=16.dp.toPx();val bottom=size.height-50.dp.toPx()
            fun x(index:Int)=if(animatedWindow<=1f) (left+right)/2 else left+(right-left)*(index-animatedFirst)/(animatedWindow-1f)
            fun y(value:Number)=bottom-(bottom-top)*value.toFloat()/animatedCeiling.coerceAtLeast(1f)
            (0..ceiling step step).forEach{value->
                if(y(value)<top-1f) return@forEach
                drawLine(grid,Offset(left,y(value)),Offset(right,y(value)),1.dp.toPx())
                val text=measurer.measure(value.toString(),labelStyle,maxLines=1,softWrap=false)
                drawText(text,topLeft=Offset((left-text.size.width-8.dp.toPx()).coerceAtLeast(0f),y(value)-text.size.height/2))
            }
            val maxLabels=((right-left)/64.dp.toPx()).toInt().coerceAtLeast(2)
            repairChartTicks(visible,maxLabels).forEach{offset->
                val index=first+offset;val date=dates[index]
                val primary=if(monthly) "${date.monthValue}月" else date.format(DateTimeFormatter.ofPattern("MM-dd"))
                val secondary=if(monthly) "${date.year}" else "周${"一二三四五六日"[date.dayOfWeek.value-1]}"
                listOf(primary,secondary).forEachIndexed{line,label->
                    val text=measurer.measure(label,labelStyle,maxLines=1,softWrap=false)
                    val at=(x(index)-text.size.width/2).coerceIn(0f,(size.width-text.size.width).coerceAtLeast(0f))
                    drawText(text,topLeft=Offset(at,bottom+8.dp.toPx()+line*16.dp.toPx()))
                }
            }
            clipRect(left-4.dp.toPx(),top-4.dp.toPx(),right+4.dp.toPx(),bottom+4.dp.toPx()){
                if(focus in first..last) drawLine(ink.copy(alpha=.35f),Offset(x(focus),top),Offset(x(focus),bottom),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),4.dp.toPx())))
                retained.values.forEachIndexed{seriesIndex,moving->
                    val item=moving.item
                    val color=(colors[item.key] ?: ink).copy(alpha=lineAlpha[item.key] ?: 0f)
                    val dash=if(seriesIndex>=8) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(),4.dp.toPx())) else null
                    fun valueAt(index:Int):Float? {
                        val target=item.counts[index] ?: return null
                        val old=moving.from.getOrNull(index) ?: target
                        return old+(target-old)*blend.value
                    }
                    val renderFirst=floor(animatedFirst).toInt().coerceIn(0,dates.lastIndex)
                    val renderLast=ceil(animatedFirst+animatedWindow-1).toInt().coerceIn(renderFirst,dates.lastIndex)
                    for(index in renderFirst..renderLast){
                        val value=valueAt(index) ?: continue
                        val previous=if(index>renderFirst) valueAt(index-1) else null
                        if(previous!=null){
                            val middle=(x(index-1)+x(index))/2
                            val path=Path().apply{moveTo(x(index-1),y(previous));cubicTo(middle,y(previous),middle,y(value),x(index),y(value))}
                            drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=if(item.key==ALL_REPAIR_TREND) 3.dp.toPx() else 2.dp.toPx(),pathEffect=dash))
                        }
                        if(visible<=45 || index==focus) drawCircle(color,if(index==focus) 4.dp.toPx() else 2.dp.toPx(),Offset(x(index),y(value)))
                    }
                }
            }
        }
        if(pointCount>visible){
            AnimatedLabel("可视日期：${trendDateLabel(dates[first],monthly)} — ${trendDateLabel(dates[last],monthly)}",style=MaterialTheme.typography.labelSmall)
        }
        AnimatedLabel(trendDateLabel(dates[focus.coerceIn(0,dates.lastIndex)],monthly),style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            series.take(6).forEach{item->AnimatedLabel("${item.name} ${item.counts[focus.coerceIn(0,item.counts.lastIndex)]?.toString() ?: "未知"}",color=colors[item.key] ?: ink,style=MaterialTheme.typography.labelMedium)}
        }
        if(series.size>6) AnimatedLabel("已显示${series.size}条曲线，完整人员数量见下方统计表。",style=MaterialTheme.typography.bodySmall)
    }}
}

