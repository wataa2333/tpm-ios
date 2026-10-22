package com.personal.tpm.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.tpm.data.Jv
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun PrecisionDateChart(rows: List<Jv>, days: Int, setDays: (Int) -> Unit) {
    val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    val dates = remember(today, days) { (days - 1 downTo 0).map { today.minusDays(it.toLong()) } }
    val counts = remember(rows) { rows.groupingBy { it["analysisDate"].text.take(10) }.eachCount() }
    val values = remember(dates, counts) { dates.map { counts[it.toString()] ?: 0 } }
    var selected by rememberSaveable(days) { mutableIntStateOf(days - 1) }
    var window by rememberSaveable(days) { mutableFloatStateOf(days.toFloat()) }
    var start by rememberSaveable(days) { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val scale = (ceil((values.maxOrNull() ?: 0).coerceAtLeast(1) / 4.0).toInt() * 4).coerceAtLeast(4)
    val fmt = DateTimeFormatter.ofPattern("MM-dd")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(7, 30, 90).forEach { n -> ChoiceChip(days == n, { setDays(n) }, "近${n}天") }
        }
        Text("${dates.first().format(fmt)} — ${dates.last().format(fmt)} · 共${values.sum()}台", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(210.dp).semantics {
            contentDescription = "检测日期曲线图，${dates[selected]}：${values[selected]}台。支持双指缩放和横向拖动"
        }.pointerInput(days) {
            detectTapGestures { p ->
                val left = 42.dp.toPx(); val width = (size.width - left - 14.dp.toPx()).coerceAtLeast(1f)
                selected = (start + (p.x - left) / width * (window - 1)).roundToInt().coerceIn(0, days - 1)
            }
        }.pointerInput(days) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var claimed = false
                var accumulated = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    if (!claimed && event.changes.any { it.isConsumed }) break
                    val fingers = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    accumulated += pan
                    if (!claimed) when (chartGestureIntent(fingers, accumulated.x, accumulated.y, viewConfiguration.touchSlop)) {
                        ChartGestureIntent.Chart -> claimed = true
                        ChartGestureIntent.Page -> break
                        ChartGestureIntent.Undecided -> Unit
                    }
                    if (claimed && fingers > 0) {
                        val width = (size.width - 56.dp.toPx()).coerceAtLeast(1f)
                        val fraction = ((event.calculateCentroid().x - 42.dp.toPx()) / width).coerceIn(0f, 1f)
                        val anchor = start + fraction * (window - 1)
                        val previous = window
                        val zoom = if (fingers >= 2) event.calculateZoom().takeIf { it.isFinite() && it > 0 } ?: 1f else 1f
                        window = (window / zoom).coerceIn(3f, days.toFloat())
                        start = (anchor - fraction * (window - 1) - pan.x / width * (previous - 1)).coerceIn(0f, days - window)
                        selected = selected.coerceIn(ceil(start).toInt(), floor(start + window - 1).toInt())
                        event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
            val left = 42.dp.toPx(); val right = size.width - 14.dp.toPx()
            val top = 18.dp.toPx(); val bottom = size.height - 32.dp.toPx()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); textSize = 11.sp.toPx() }
            fun point(i: Int) = Offset(left + (right - left) * (i - start) / (window - 1), bottom - (bottom - top) * values[i] / scale)
            for (n in 0..4) {
                val y = bottom - (bottom - top) * n / 4
                drawLine(grid.copy(alpha = .65f), Offset(left, y), Offset(right, y), 1.dp.toPx())
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText("${scale * n / 4}", left - 6.dp.toPx(), y + paint.textSize / 3, paint)
            }
            val first = floor(start).toInt().coerceIn(0, days - 1)
            val last = ceil(start + window - 1).toInt().coerceIn(first, days - 1)
            val path = Path().apply {
                val initial = point(first); moveTo(initial.x, initial.y)
                for (i in first + 1..last) {
                    val a = point(i - 1); val b = point(i); val mid = (a.x + b.x) / 2
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
            }
            clipRect(left - 1.dp.toPx(), top - 5.dp.toPx(), right + 1.dp.toPx(), bottom + 5.dp.toPx()) {
                drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
                if (window <= 15) for (i in first..last) drawCircle(accent, 2.dp.toPx(), point(i))
                if (selected.toFloat() in start..(start + window - 1)) {
                    val focus = point(selected)
                    drawLine(accent.copy(alpha = .35f), Offset(focus.x, top), Offset(focus.x, bottom), 1.dp.toPx())
                    drawCircle(surface, 5.dp.toPx(), focus); drawCircle(accent, 3.dp.toPx(), focus)
                }
            }
            val ticks = (0..3).map { (start + (window - 1) * it / 3).roundToInt().coerceIn(0, days - 1) }.distinct()
            ticks.forEachIndexed { n, i ->
                paint.textAlign = when (n) { 0 -> Paint.Align.LEFT; ticks.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                val x = when (n) { 0 -> left; ticks.lastIndex -> right; else -> point(i).x }
                drawContext.canvas.nativeCanvas.drawText(dates[i].format(fmt), x, bottom + 21.dp.toPx(), paint)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("检测日期：${dates[selected].format(fmt)}", style = MaterialTheme.typography.bodyMedium)
            Text("${values[selected]}台", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
