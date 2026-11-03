package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent

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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
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
        fadeInSpec = if (skeleton) null else tween(Motion.Slow, easing = Motion.Change), placementSpec = if (skeleton) null else tween(Motion.Normal, easing = Motion.Change),
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
    val lineGap = 8.dp
    val footerHeight = 48.dp
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
    val pool = poolKindForTask(task)
    val fault = kind != OrderKind.Erp
    return CardModel(
        key = task["id"].text, title = pool?.let { data[it.nameKey].text.ifEmpty { todoTitle(task) } } ?: todoTitle(task), status = todoStatus(task),
        colorCode = if (fault) data["faultState"].text else data["approvalState"].text, isApproval = !fault,
        reference = if (fault) data["asset"]["assetCode"].text.ifEmpty { "—" } + data["asset"]["addressCode"].text.let { if (it.isEmpty()) "" else " · $it" } else data["outNumber"].text.ifEmpty { "—" },
        time = task["createTime"].dateText,
        facts = listOf("流程类型" to (pool?.title ?: kind?.title ?: "其他流程"), "当前节点" to task["name"].text) + when (kind) { OrderKind.Fault -> faultCardFacts(data); OrderKind.Erp -> erpCardFacts(data); null -> emptyList() },
        kind = kind, openId = task["businessKey"].text, cardType = when (kind) { OrderKind.Fault -> CardType.TodoFault; OrderKind.Erp -> CardType.TodoErp; null -> CardType.TodoOther },
    )
}

/**
 * One order. Title, state, reference and time are always there; "展开详情" opens the rest in place (the whole block is one selection area:
 * long-press a value to copy it); tapping the title (or "打开工单") opens the order.
 */


@Composable
fun OrderCard(model: CardModel, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, selecting: CardSelecting? = null, bottomAction: (@Composable () -> Unit)? = null) {
    val selectionInset by androidx.compose.animation.core.animateDpAsState(
        if (selecting != null) 40.dp else 0.dp, tween(Motion.Normal, easing = Motion.Change), label = "card selection inset")
    val order = FixedCardLayout.orderedFields(model.cardType)
    val configured = configureCard(model, { FixedCardLayout.position(model.cardType, it) }) { field, default -> FixedCardLayout.placement(model.cardType, field, default) }
    val body = listOfNotNull(configured.reference.takeIf { it.isNotEmpty() }?.let { "编号" to it }) +
        configured.summary.sortedBy { order.indexOf(it.first) } + listOfNotNull(configured.time.takeIf { it.isNotEmpty() }?.let { "时间" to it })
    val buttonSlot = FixedCardLayout.detailButtonSlot(model.cardType)
    val initialGrid = compactLayout(body, configured.facts.isNotEmpty(),
        buttonSlot,
        { FixedCardLayout.slot(model.cardType, it) }, { FixedCardLayout.position(model.cardType, it) })
    val grid = if (configured.facts.isEmpty() && initialGrid.overflow.isNotEmpty()) compactLayout(body, true,
        buttonSlot,
        { FixedCardLayout.slot(model.cardType, it) }, { FixedCardLayout.position(model.cardType, it) }) else initialGrid
    val shownModel = configured.copy(facts = (configured.facts + grid.overflow).distinctBy { it.first }.sortedBy { order.indexOf(it.first) })
    val haptic = LocalHapticFeedback.current
    val detailButton: @Composable () -> Unit = {
        DetailToggle(expanded, { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggle() })
    }
    val tint = if (shownModel.isApproval) approvalColor(shownModel.colorCode) else stateColor(shownModel.colorCode)
    Card(
        // The whole card opens the order (a card with nothing to open expands instead). While orders are being selected it opens a read-only peek.
        onClick = selecting?.peek ?: onOpen ?: onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        // The standard Material shadow (a card at 2 dp, 4 dp while pressed), no outline.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Box(Modifier.padding(CardMetrics.padding)) {
        Column(Modifier.padding(start = selectionInset)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedLabel(shownModel.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (shownModel.status.isNotEmpty()) AnimatedLabel(shownModel.status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            }
            Spacer(Modifier.height(CardMetrics.lineGap))
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier) {
                    CompactCardGrid(grid.cells, model.cardType, detailButton)
                }
            }
            // The facts open and close with one motion: the space grows (or closes) on an ease-in-out curve, and the rows fade in step, so
            // the cards below move exactly as much as the space does, no earlier and no later (their own placement is not animated a second
            // time). The rows are plain text inside a single selection area: dozens of separate selection containers made opening stutter.
            Reveal(visible = expanded) {
                Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                    val facts: @Composable () -> Unit = {
                        FactsGrid(shownModel.facts, model.cardType)
                    }
                    SelectionContainer { facts() }
                    if (onOpen != null && selecting == null) GlassActionButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("打开工单", maxLines = 1) }
                    if(selecting==null) bottomAction?.invoke()
                }
            }
        }
        selecting?.let { Box(Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = (selectionInset / 40.dp).coerceIn(0f, 1f) }) { SelectCircle(it) } }
        }
    }
}

@Composable
private fun CompactCardGrid(cells: List<CompactCell>, type: CardType, button: @Composable () -> Unit) {
    val text = cells.filter { it.field != DETAIL_BUTTON }.sortedBy { it.slot }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CardMetrics.lineGap)) {
            text.forEach { cell ->
                Text((if (FixedCardLayout.showLabel(type, cell.field)) cell.field + " " else "") + cell.value,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (cells.any { it.field == DETAIL_BUTTON }) button()
    }
}

@Composable
private fun DetailToggle(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.Normal, easing = Motion.Change), label = "chevron")
    GlassTonalButton(onClick = onClick, contentPadding = PaddingValues(start = 14.dp, end = 10.dp), modifier = modifier.height(CardMetrics.footerHeight)) {
        AnimatedLabel(if (expanded) "收起详情" else "展开详情", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
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
                .drawWithContent {
                    if (progress.value < 1f) clipRect { this@drawWithContent.drawContent() } else drawContent()
                }
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
private fun SummaryGrid(items: List<Pair<String, String>>, type: CardType) {
    val rows = summaryRows(items) { FixedCardLayout.position(type, it) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { index, (label, value) ->
                    if (index == 1 && row.all { it.first.isNotEmpty() }) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f, fill = false)) {
                        if (label.isNotEmpty() && FixedCardLayout.showLabel(type, label)) {
                            AnimatedLabel(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(6.dp))
                        }
                        AnimatedLabel(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactsGrid(items: List<Pair<String, String>>, type: CardType) {
    val rows = summaryRows(items) { field -> FixedCardLayout.position(type, field).let { if (it == FieldPosition.Auto) FieldPosition.Full else it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            if (row.size == 1) PlainFact(row[0].first, row[0].second)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { index, (label, value) ->
                    if (index == 1 && row.all { it.first.isNotEmpty() }) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f, fill = false)) {
                        if (label.isNotEmpty()) {
                            AnimatedLabel(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AnimatedLabel(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Native animation drives a draw-only highlight sweep while preserving placeholder geometry. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, cardType: CardType = CardType.Fault) {
    val plan = skeletonPlan(cardType, { FixedCardLayout.position(cardType, it) }, FixedCardLayout.orderedFields(cardType), FixedCardLayout.detailButtonPosition(cardType)) { field, default -> FixedCardLayout.placement(cardType, field, default) }
    val preview = configureCard(previewCard(cardType), { FixedCardLayout.position(cardType, it) }) { field, default -> FixedCardLayout.placement(cardType, field, default) }
    val body = listOfNotNull(preview.reference.takeIf { it.isNotEmpty() }?.let { "编号" to it }) + preview.summary +
        listOfNotNull(preview.time.takeIf { it.isNotEmpty() }?.let { "时间" to it })
    val grid = compactLayout(body, preview.facts.isNotEmpty(), FixedCardLayout.detailButtonSlot(cardType), { FixedCardLayout.slot(cardType, it) }, { FixedCardLayout.position(cardType, it) })
    val bodyRows = grid.cells.count { it.field != DETAIL_BUTTON }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surface.copy(alpha = .7f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton sweep")
    val sweep = transition.animateFloat(initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "skeleton light")
    fun Modifier.flow() = this.background(base).drawWithContent {
        drawContent()
        val band = size.width * .65f
        val x = (size.width + band * 2) * sweep.value - band
        drawRect(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, light, androidx.compose.ui.graphics.Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(x - band, 0f), end = androidx.compose.ui.geometry.Offset(x + band, size.height)))
    }
    // Everything is measured like the real card: the height of a line is the line height of the text it stands for (so it follows the interface
    // size and the font size), and the sweep of light is measured in dp, not pixels.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleLine = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
    val bodyLine = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    fun bar(width: Float, height: androidx.compose.ui.unit.Dp) = Modifier.fillMaxWidth(width).height(height).clip(RoundedCornerShape(6.dp))
        .flow()
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(CardMetrics.padding), verticalArrangement = Arrangement.spacedBy(CardMetrics.lineGap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(bar(if (plan.status) .68f else .85f, titleLine))
                if (plan.status) Box(Modifier.weight(1f).height(titleLine).clip(RoundedCornerShape(6.dp)).flow())
            }
            if (plan.reference || plan.summaryLines > 0 || plan.footer || plan.topButton) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CardMetrics.lineGap)) {
                        repeat(bodyRows) { row -> Box(bar(if (row == 0) .8f else .55f, bodyLine)) }
                    }
                    if (grid.cells.any { it.field == DETAIL_BUTTON }) Box(Modifier.width(104.dp).height(CardMetrics.footerHeight).clip(CircleShape).flow())
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String = "暂无相关单据") {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedLabel(title, style = MaterialTheme.typography.titleMedium)
        Text("可切换状态或调整搜索条件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
