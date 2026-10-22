package com.personal.tpm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.approvalStateName
import com.personal.tpm.data.erpCardFacts
import com.personal.tpm.data.erpCardTitle
import com.personal.tpm.data.faultCardFacts
import com.personal.tpm.data.faultStateLabel
import com.personal.tpm.data.todoKind
import com.personal.tpm.data.todoStatus
import com.personal.tpm.data.todoTitle
import com.personal.tpm.ui.theme.ThemeConfig

/**
 * A list item that fades in when it appears. The grey skeleton cards ([skeleton] = true) fade out when they go, so they melt into the real cards;
 * real cards never fade out: when a refresh brings a list whose rows moved, the rows that left would otherwise stay on screen for a moment,
 * fading, over the new ones (that showed as a stutter at the end of every pull-down refresh).
 */
fun androidx.compose.foundation.lazy.LazyItemScope.fadeItem(skeleton: Boolean = false): Modifier =
    Modifier.animateItem(
        fadeInSpec = if (skeleton) null else tween(Motion.Slow, easing = Motion.Change), placementSpec = null,
        fadeOutSpec = if (skeleton) tween(Motion.Normal, easing = Motion.Change) else null,
    )

/** The colour of an order state, for the current light or dark look. */
@Composable
fun stateColor(code: String): Color = ThemeConfig.stateColor(code, MaterialTheme.colorScheme.background.luminance() < 0.5f)

@Composable
fun approvalColor(code: String): Color = ThemeConfig.approvalColor(code, MaterialTheme.colorScheme.background.luminance() < 0.5f)

/** Row metrics shared by every card: the same inner padding, the same gap between lines, the same footer height. */
object CardMetrics {
    val padding = 16.dp
    val lineGap = 6.dp
    val footerHeight = 32.dp
}

/** What a card shows, whatever order it is: a repair, an ERP outbound order, a todo task. */
@Immutable
data class CardModel(
    val key: String,
    val title: String,
    val status: String,
    /** The repair state code or the ERP approval code the status colour comes from. */
    val colorCode: String,
    val isApproval: Boolean,
    val reference: String,
    val time: String,
    val facts: List<Pair<String, String>>,
    val kind: OrderKind?,
    /** The id the order is opened by (faultId, outboundId). */
    val openId: String,
    /** Lines always shown on the card (label, value), laid out two to a row; short values pair up, long ones take a row of their own. */
    val summary: List<Pair<String, String>> = emptyList(),
    val cardType: CardType = if (kind == OrderKind.Erp) CardType.Erp else CardType.Fault,
)

fun faultCardModel(item: Jv) = CardModel(
    key = item["faultId"].text, title = item["faultName"].text, status = faultStateLabel(item), colorCode = item["faultState"].text, isApproval = false,
    reference = item["asset"]["assetCode"].text.ifEmpty { "—" } + item["asset"]["addressCode"].text.let { if (it.isEmpty()) "" else " · $it" },
    time = item["faultTime"].dateText, facts = faultCardFacts(item), kind = OrderKind.Fault, openId = item["faultId"].text,
)

fun erpCardModel(item: Jv) = CardModel(
    key = item["outboundId"].text, title = erpCardTitle(item), status = approvalStateName(item["approvalState"].text), colorCode = item["approvalState"].text, isApproval = true,
    reference = item["outNumber"].text.ifEmpty { "—" }, time = item["applyDate"].dateText, facts = erpCardFacts(item), kind = OrderKind.Erp, openId = item["outboundId"].text,
)

fun todoCardModel(task: Jv): CardModel {
    val data = task["data"]
    val kind = todoKind(task)
    val fault = kind != OrderKind.Erp
    return CardModel(
        key = task["id"].text, title = todoTitle(task), status = todoStatus(task),
        colorCode = if (fault) data["faultState"].text else data["approvalState"].text, isApproval = !fault,
        reference = if (fault) data["asset"]["assetCode"].text.ifEmpty { "—" } + data["asset"]["addressCode"].text.let { if (it.isEmpty()) "" else " · $it" } else data["outNumber"].text.ifEmpty { "—" },
        time = task["createTime"].dateText,
        facts = listOf("流程类型" to (kind?.title ?: "其他流程"), "当前节点" to task["name"].text) + when (kind) { OrderKind.Fault -> faultCardFacts(data); OrderKind.Erp -> erpCardFacts(data); null -> emptyList() },
        kind = kind, openId = task["businessKey"].text, cardType = when (kind) { OrderKind.Fault -> CardType.TodoFault; OrderKind.Erp -> CardType.TodoErp; null -> CardType.TodoOther },
    )
}

/**
 * One order. Title, state, reference and time are always there; "展开详情" opens the rest in place (the whole block is one selection area:
 * long-press a value to copy it); tapping the title (or "打开工单") opens the order.
 */
class CardLayoutEditing(val onFieldClick: (String) -> Unit, val onCompactBounds: (androidx.compose.ui.geometry.Rect) -> Unit, val reserveRows: Boolean = false, val fieldModifier: (String) -> Modifier)

@Composable
fun OrderCard(model: CardModel, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, selecting: CardSelecting? = null, editing: CardLayoutEditing? = null, bottomAction: (@Composable () -> Unit)? = null) {
    val order = CardPreferences.orderedFields(model.cardType)
    val configured = configureCard(model, { CardPreferences.position(model.cardType, it) }) { field, default -> CardPreferences.placement(model.cardType, field, default) }
    val body = listOfNotNull(configured.reference.takeIf { it.isNotEmpty() }?.let { "编号" to it }) +
        configured.summary.sortedBy { order.indexOf(it.first) } + listOfNotNull(configured.time.takeIf { it.isNotEmpty() }?.let { "时间" to it })
    val buttonSlot = CardPreferences.detailButtonSlot(model.cardType)
    val initialGrid = compactLayout(body, configured.facts.isNotEmpty(),
        buttonSlot,
        { CardPreferences.slot(model.cardType, it) }, { CardPreferences.position(model.cardType, it) })
    val grid = if (configured.facts.isEmpty() && initialGrid.overflow.isNotEmpty()) compactLayout(body, true,
        buttonSlot,
        { CardPreferences.slot(model.cardType, it) }, { CardPreferences.position(model.cardType, it) }) else initialGrid
    val shownModel = configured.copy(facts = (configured.facts + grid.overflow).distinctBy { it.first }.sortedBy { order.indexOf(it.first) })
    val haptic = LocalHapticFeedback.current
    val detailButton: @Composable () -> Unit = {
        DetailToggle(expanded, { if (editing != null) editing.onFieldClick(DETAIL_BUTTON) else { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggle() } }, editing?.fieldModifier?.invoke(DETAIL_BUTTON) ?: Modifier)
    }
    val tint = if (shownModel.isApproval) approvalColor(shownModel.colorCode) else stateColor(shownModel.colorCode)
    Card(
        // The whole card opens the order (a card with nothing to open expands instead). While orders are being selected it opens a read-only peek.
        onClick = if (editing != null) ({}) else selecting?.peek ?: onOpen ?: onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        // The standard Material shadow (a card at 2 dp, 4 dp while pressed), no outline.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Column(Modifier.padding(CardMetrics.padding)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shownModel.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (shownModel.status.isNotEmpty()) Text(shownModel.status, modifier = editing?.fieldModifier?.invoke("状态") ?: Modifier, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            }
            Spacer(Modifier.height(CardMetrics.lineGap))
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(start = if (selecting != null) 40.dp else 0.dp)) {
                    CompactCardGrid(grid.cells, model.cardType, editing, detailButton)
                }
                selecting?.let { Box(Modifier.align(Alignment.CenterStart)) { SelectCircle(it) } }
            }
            // The facts open and close with one motion: the space grows (or closes) on an ease-in-out curve, and the rows fade in step, so
            // the cards below move exactly as much as the space does, no earlier and no later (their own placement is not animated a second
            // time). The rows are plain text inside a single selection area: dozens of separate selection containers made opening stutter.
            Reveal(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    val facts: @Composable () -> Unit = {
                        FactsGrid(shownModel.facts, model.cardType, editing)
                    }
                    if (editing == null) SelectionContainer { facts() } else facts()
                    if (onOpen != null && selecting == null) Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("打开工单", maxLines = 1) }
                    if(selecting==null && editing==null) bottomAction?.invoke()
                }
            }
        }
    }
}

@Composable
private fun CompactCardGrid(cells: List<CompactCell>, type: CardType, editing: CardLayoutEditing?, button: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val rows = if (editing?.reserveRows == true) 3 else ((cells.maxOfOrNull { it.slot / 2 } ?: -1) + 1)
    Column(Modifier.fillMaxWidth().onGloballyPositioned { editing?.onCompactBounds?.invoke(it.boundsInRoot()) }) {
        repeat(rows) { row ->
            Box(Modifier.fillMaxWidth().height(CardMetrics.footerHeight + CardMetrics.lineGap)) {
                val content = cells.filter { it.slot / 2 == row }.sortedBy { it.slot }
                val parts = buildList<CompactCell?> {
                    content.forEachIndexed { index, cell ->
                        if (index > 0 && cell.field != DETAIL_BUTTON && content[index - 1].field != DETAIL_BUTTON) add(null)
                        add(cell)
                    }
                }
                androidx.compose.ui.layout.Layout(modifier = Modifier.fillMaxWidth().height(CardMetrics.footerHeight), content = {
                    parts.forEach { cell ->
                        if (cell == null) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (cell.field == DETAIL_BUTTON) button()
                        else Text((if (CardPreferences.showLabel(type, cell.field)) cell.field + " " else "") + cell.value,
                            modifier = editing?.fieldModifier?.invoke(cell.field) ?: Modifier,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }) { measurables, constraints ->
                    val gap = 6.dp.roundToPx()
                    val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(constraints.maxWidth) }
                    val textIndices = parts.indices.filter { parts[it] != null && parts[it]?.field != DETAIL_BUTTON }
                    val fixed = parts.indices.filter { it !in textIndices }
                    val room = (constraints.maxWidth - gap * (parts.size - 1).coerceAtLeast(0) - fixed.sumOf { natural[it] }).coerceAtLeast(0)
                    val textWidths = inlineCardWidths(textIndices.map { natural[it] }, room)
                    val widths = natural.toMutableList()
                    textIndices.forEachIndexed { index, at -> widths[at] = textWidths[index] }
                    val placed = measurables.mapIndexed { index, measurable -> measurable.measure(Constraints(maxWidth = widths[index], maxHeight = constraints.maxHeight)) }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        var x = 0
                        placed.forEachIndexed { index, item ->
                            if (parts[index]?.field == DETAIL_BUTTON && parts[index]!!.slot % 2 == 1) x = maxOf(x, constraints.maxWidth - item.width)
                            item.placeRelative(x, (constraints.maxHeight - item.height) / 2)
                            x += item.width + gap
                        }
                    }
                }
                if (editing != null) androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(CardMetrics.footerHeight + CardMetrics.lineGap)) {
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                    drawLine(accent.copy(alpha = .35f), androidx.compose.ui.geometry.Offset(0f, size.height - 1), androidx.compose.ui.geometry.Offset(size.width, size.height - 1), pathEffect = dash)
                }
            }
        }
    }
}

@Composable
private fun DetailToggle(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.Normal, easing = Motion.Change), label = "chevron")
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(start = 14.dp, end = 10.dp), modifier = modifier.height(CardMetrics.footerHeight)) {
        Text(if (expanded) "收起详情" else "展开详情", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        Icon(Icons.Filled.ExpandMore, contentDescription = null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
    }
}

/**
 * Content that opens and closes in place. Two eased animations run together: the height (the room the content takes, which is what moves
 * everything below it) and the fade of the content itself. Closing, the content fades faster than the room closes, so it is already
 * mostly gone when the cards below start to come up; opening, the room opens first and the content fades in over it.
 */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    // One animation for both the room and the fade (the fade is the same value, shaped by a curve), started only when this card is really opened
    // or closed: a list holds dozens of closed cards, none of which may start anything.
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val target = if (visible) 1f else 0f
    LaunchedEffect(target) {
        if (progress.targetValue != target) progress.animateTo(target, tween(if (visible) Motion.Slow else Motion.Normal, easing = if (visible) Motion.Arrive else Motion.Leave))
    }
    // Whether anything is left to show is read through a derived state, so the body is not recomposed on every frame of the animation.
    val present by remember { androidx.compose.runtime.derivedStateOf { progress.value > 0f } }
    if (visible || present) {
        Box(
            Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val height = (placeable.height * progress.value).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
                // ModulateAlpha fades each piece as it is drawn: no off-screen copy of the whole block is made (that was the slow part).
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    alpha = ((progress.value - 0.15f) / 0.85f).coerceIn(0f, 1f)
                },
        ) { content() }
    }
}

/** The circle at the left of a card in selection mode: ticked when selected, faded when this card cannot be selected with what is already chosen. */
@Composable
private fun SelectCircle(selecting: CardSelecting) {
    // The touch ripple is a circle too (it was a square), and the tick springs in a little.
    val pop by animateFloatAsState(if (selecting.selected) 1f else 0.85f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "tick")
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = selecting.toggle), Alignment.Center) {
        Icon(
            if (selecting.selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (selecting.selected) "已选" else "选择", modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
            tint = if (selecting.selected) MaterialTheme.colorScheme.primary else if (selecting.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** The lines a card always shows, in as few rows as fit: two short ones side by side, a long one alone; one line each, cut with "…". */
@Composable
private fun SummaryGrid(items: List<Pair<String, String>>, type: CardType, editing: CardLayoutEditing?) {
    val rows = summaryRows(items) { CardPreferences.position(type, it) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { index, (label, value) ->
                    if (index == 1 && row.all { it.first.isNotEmpty() }) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f, fill = false).then(if (label.isEmpty()) Modifier else editing?.fieldModifier?.invoke(label) ?: Modifier)) {
                        if (label.isNotEmpty() && CardPreferences.showLabel(type, label)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactsGrid(items: List<Pair<String, String>>, type: CardType, editing: CardLayoutEditing?) {
    val rows = summaryRows(items) { field -> CardPreferences.position(type, field).let { if (it == FieldPosition.Auto) FieldPosition.Full else it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            if (row.size == 1) PlainFact(row[0].first, row[0].second, editing?.fieldModifier?.invoke(row[0].first) ?: Modifier)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { index, (label, value) ->
                    if (index == 1 && row.all { it.first.isNotEmpty() }) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f, fill = false).then(if (label.isEmpty()) Modifier else editing?.fieldModifier?.invoke(label) ?: Modifier)) {
                        if (label.isNotEmpty()) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainFact(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A grey stand-in for a card while the first page loads: a soft light sweeps across it, and it fades out as the real cards come in. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, cardType: CardType = CardType.Fault) {
    val plan = skeletonPlan(cardType, { CardPreferences.position(cardType, it) }, CardPreferences.orderedFields(cardType), CardPreferences.detailButtonPosition(cardType)) { field, default -> CardPreferences.placement(cardType, field, default) }
    val preview = configureCard(previewCard(cardType), { CardPreferences.position(cardType, it) }) { field, default -> CardPreferences.placement(cardType, field, default) }
    val body = listOfNotNull(preview.reference.takeIf { it.isNotEmpty() }?.let { "编号" to it }) + preview.summary +
        listOfNotNull(preview.time.takeIf { it.isNotEmpty() }?.let { "时间" to it })
    val grid = compactLayout(body, preview.facts.isNotEmpty(), CardPreferences.detailButtonSlot(cardType), { CardPreferences.slot(cardType, it) }, { CardPreferences.position(cardType, it) })
    val bodyRows = (grid.cells.maxOfOrNull { it.slot / 2 } ?: -1) + 1
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surfaceContainerLowest
    // Everything is measured like the real card: the height of a line is the line height of the text it stands for (so it follows the interface
    // size and the font size), and the sweep of light is measured in dp, not pixels.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleLine = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
    val bodyLine = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    val band = with(density) { 220.dp.toPx() }
    val travel = with(density) { 440.dp.toPx() }
    fun bar(width: Float, height: androidx.compose.ui.unit.Dp) = Modifier.fillMaxWidth(width).height(height).clip(RoundedCornerShape(6.dp))
        .background(Brush.horizontalGradient(listOf(base, light, base), startX = -band + travel * x, endX = band + travel * x))
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(CardMetrics.padding), verticalArrangement = Arrangement.spacedBy(CardMetrics.lineGap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(bar(if (plan.status) .68f else .85f, titleLine))
                if (plan.status) Box(Modifier.weight(1f).height(titleLine).clip(RoundedCornerShape(6.dp)).background(base))
            }
            if (plan.reference || plan.summaryLines > 0 || plan.footer || plan.topButton) {
                Column {
                    repeat(bodyRows) { row -> Box(Modifier.fillMaxWidth().height(CardMetrics.footerHeight + CardMetrics.lineGap)) {
                        Box(bar(if (row == 0) .8f else .55f, bodyLine))
                    } }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String = "暂无相关单据") {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("可切换状态或调整搜索条件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
