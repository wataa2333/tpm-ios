package com.personal.tpm.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn as NativeLazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll as nativeVerticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Thumb is visible whenever the page has a scroll range. Only the rightmost 20dp captures dragging. */
private fun Modifier.pageScrollbar(position:()->Float, fraction:()->Float, canScroll:()->Boolean,
    seek:suspend(Float)->Unit):Modifier = composed {
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    val context=LocalContext.current
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val systemThumb=remember(context,configuration.uiMode) {
        val attrs=context.obtainStyledAttributes(null,intArrayOf(android.R.attr.scrollbarThumbVertical),android.R.attr.scrollViewStyle,0)
        try { attrs.getDrawable(0)?.mutate() } finally { attrs.recycle() }
    }
    var visible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    val currentPosition by rememberUpdatedState(position)
    val currentFraction by rememberUpdatedState(fraction)
    LaunchedEffect(Unit) {
        snapshotFlow { currentPosition() to currentFraction() }.collectLatest {
            visible = true
            delay(1200)
            visible = false
        }
    }
    val opacity by animateFloatAsState(if(visible || dragging) 1f else 0f,tween(Motion.Fast,easing=Motion.Change),label="system scrollbar fade")
    val targets=remember { Channel<Float>(Channel.CONFLATED) }
    val currentSeek by rememberUpdatedState(seek)
    val currentCanScroll by rememberUpdatedState(canScroll)
    LaunchedEffect(targets) { for(target in targets) currentSeek(target) }
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            if(!currentCanScroll() || down.position.x<size.width-20.dp.toPx()) return@awaitEachGesture
            fun seekAt(y:Float) {
                val height=(size.height*currentFraction().coerceIn(0f,1f)).coerceIn(24.dp.toPx().coerceAtMost(size.height.toFloat()),size.height.toFloat())
                val travel=(size.height-height).coerceAtLeast(1f)
                targets.trySend(((y-height/2)/travel).coerceIn(0f,1f))
            }
            down.consume(); dragging=true; seekAt(down.position.y)
            try {
            do {
                val event=awaitPointerEvent()
                event.changes.firstOrNull{it.id==down.id}?.let { change ->
                    if(change.pressed) seekAt(change.position.y)
                    change.consume()
                }
            } while(event.changes.any{it.pressed})
            } finally { dragging=false }
        }
    }.drawWithContent {
        drawContent()
        if(canScroll() && size.height>0f) {
            val width=3.dp.toPx()
            val height=(size.height*fraction().coerceIn(0f,1f)).coerceIn(24.dp.toPx().coerceAtMost(size.height),size.height)
            val top=(size.height-height)*position().coerceIn(0f,1f)
            if(systemThumb!=null) {
                systemThumb.alpha=(255*opacity).toInt()
                systemThumb.setBounds((size.width-width).toInt(),top.toInt(),size.width.toInt(),(top+height).toInt())
                systemThumb.draw(drawContext.canvas.nativeCanvas)
            } else drawRoundRect(ink.copy(alpha=.45f*opacity),Offset(size.width-width,top),Size(width,height),CornerRadius(width))
        }
    }
}

/** Drop-in list wrapper: overlay is measured after the list, so short dialogs keep their natural height. */
@Composable
fun ScrollableLazyColumn(modifier:Modifier=Modifier,state:LazyListState=rememberLazyListState(),
    contentPadding:PaddingValues=PaddingValues(0.dp),reverseLayout:Boolean=false,
    verticalArrangement:Arrangement.Vertical=if(reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment:Alignment.Horizontal=Alignment.Start,
    flingBehavior:FlingBehavior=ScrollableDefaults.flingBehavior(),userScrollEnabled:Boolean=true,
    content:LazyListScope.()->Unit) {
    fun fraction():Float {
        val info=state.layoutInfo
        if(info.totalItemsCount==0) return 1f
        val viewport=(info.viewportEndOffset-info.viewportStartOffset).coerceAtLeast(1)
        val visible=info.visibleItemsInfo
        if (visible.isEmpty()) return 1f
        val average=visible.sumOf { it.size }.toFloat() / visible.size
        if (average <= 0f) return 1f
        return (viewport/(average*info.totalItemsCount)).toFloat().coerceIn(.04f,1f)
    }
    val surface = LocalScrollSurface.current ?: MaterialTheme.colorScheme.background
    val sheetEdges = AppPreferences.liquidGlass && LocalScrollSurface.current != null
    val direction = LocalLayoutDirection.current
    val sheetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val glassPadding = LocalGlassContentPadding.current
    val listPadding = PaddingValues(start = contentPadding.calculateStartPadding(direction), end = contentPadding.calculateEndPadding(direction),
        top = contentPadding.calculateTopPadding() + glassPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + maxOf(sheetBottom, glassPadding.calculateBottomPadding()))
    Box(modifier.then(if (AppPreferences.liquidGlass) Modifier else Modifier.scrollEdges(surface,
        before = { if (reverseLayout) state.canScrollForward else state.canScrollBackward },
        after = { if (reverseLayout) state.canScrollBackward else state.canScrollForward })),propagateMinConstraints=true) {
        CompositionLocalProvider(LocalGlassContentPadding provides PaddingValues(0.dp)) {
            NativeLazyColumn(state=state,contentPadding=listPadding,reverseLayout=reverseLayout,
                verticalArrangement=verticalArrangement,horizontalAlignment=horizontalAlignment,
                flingBehavior=flingBehavior,userScrollEnabled=userScrollEnabled,content=content)
        }
        Box(Modifier.matchParentSize()) {
            if (sheetEdges) {
                SheetScrollEdge(surface, top = true,
                    visible = if (reverseLayout) state.canScrollForward else state.canScrollBackward,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp))
                SheetScrollEdge(surface, top = false,
                    visible = if (reverseLayout) state.canScrollBackward else state.canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp))
            }
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp).pageScrollbar(
                position={
                    val info=state.layoutInfo
                    val first=info.visibleItemsInfo.firstOrNull()
                    val unit=state.firstVisibleItemIndex+(state.firstVisibleItemScrollOffset.toFloat()/(first?.size ?: 1).coerceAtLeast(1))
                    val progress=when { !state.canScrollBackward->0f; !state.canScrollForward->1f; else->unit/(info.totalItemsCount*(1-fraction())).coerceAtLeast(.001f) }
                    if(reverseLayout) 1-progress else progress
                },fraction=::fraction,
                canScroll={userScrollEnabled && (state.canScrollForward || state.canScrollBackward)},
                seek={value->
                    val count=state.layoutInfo.totalItemsCount
                    if(count>0) {
                        val progress=if(reverseLayout) 1-value else value
                        val units=progress*count*(1-fraction())
                        val index=units.toInt().coerceIn(0,count-1)
                        val info=state.layoutInfo.visibleItemsInfo
                        val height=info.firstOrNull{it.index==index}?.size ?: info.map{it.size}.average().takeIf{it.isFinite()}?.toInt() ?: 1
                        state.scrollToItem(index,((units-index)*height).toInt().coerceAtLeast(0))
                    }
                }))
        }
    }
}

fun Modifier.pageVerticalScroll(state:ScrollState,enabled:Boolean=true,flingBehavior:FlingBehavior?=null,
    reverseScrolling:Boolean=false):Modifier = composed {
    val surface = LocalScrollSurface.current ?: MaterialTheme.colorScheme.background
    this.then(if (AppPreferences.liquidGlass) Modifier else Modifier.scrollEdges(surface, before = { if (reverseScrolling) state.canScrollForward else state.canScrollBackward },
        after = { if (reverseScrolling) state.canScrollBackward else state.canScrollForward })).pageScrollbar(
        position={val progress=state.value.toFloat()/state.maxValue.coerceAtLeast(1);if(reverseScrolling) 1-progress else progress},
        fraction={state.viewportSize.toFloat()/(state.viewportSize.toLong()+state.maxValue).coerceAtLeast(1)},
        canScroll={enabled && state.maxValue in 1 until Int.MAX_VALUE},
        seek={value->state.scrollTo(((if(reverseScrolling) 1-value else value)*state.maxValue).toInt())}
    ).nativeVerticalScroll(state,enabled,flingBehavior,reverseScrolling)
        .padding(top = LocalGlassContentPadding.current.calculateTopPadding(),
            bottom = maxOf(LocalGlassContentPadding.current.calculateBottomPadding(), WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
}
