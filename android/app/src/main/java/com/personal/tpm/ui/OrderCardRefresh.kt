package com.personal.tpm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv

internal class OrderCardResults(
    val rows: List<Jv> = emptyList(),
    private val query: String? = null,
    val lastPage: Boolean = true,
    private val replacesSameQuery: Boolean = false,
) {
    fun replace(next: List<Jv>, query: String, lastPage: Boolean) =
        OrderCardResults(next, query, lastPage, replacesSameQuery = rows.isNotEmpty() && query == this.query)

    fun append(next: List<Jv>, lastPage: Boolean) = OrderCardResults(rows + next, query, lastPage)

    fun matchesQuery(value: String): Boolean = query == value

    fun canAnimateFrom(previous: OrderCardResults): Boolean =
        replacesSameQuery && query == previous.query
}

internal data class KeyedOrderCard(val key: String, val id: String, val row: Jv)

internal fun keyedOrderCards(rows: List<Jv>, idField: String): List<KeyedOrderCard> {
    val occurrences = mutableMapOf<String, Int>()
    return rows.mapIndexed { index, row ->
        val id = row[idField].text
        val key = if (id.isBlank()) {
            "card-missing:$index"
        } else {
            val occurrence = occurrences.getOrDefault(id, 0)
            occurrences[id] = occurrence + 1
            "card:$id#$occurrence"
        }
        KeyedOrderCard(key, id, row)
    }
}

internal fun removedOrderCardKeys(
    previous: List<KeyedOrderCard>,
    next: List<KeyedOrderCard>,
    lastPage: Boolean,
): Set<String> {
    val nextIds = next.mapTo(mutableSetOf()) { it.id }
    // A refreshed first page can push old rows beyond its end. Stop at the last shared row.
    val end = if (lastPage) previous.size else previous.indexOfLast { it.id.isNotBlank() && it.id in nextIds } + 1
    val previousCounts = previous.groupingBy { it.id }.eachCount()
    return previous.take(end).filter {
        it.id.isNotBlank() && previousCounts[it.id] == 1 && it.id !in nextIds
    }.mapTo(mutableSetOf()) { it.key }
}

internal class DisplayedOrderCards(
    val rows: List<KeyedOrderCard>,
    val updating: Boolean,
    private val leaving: Set<String>,
    private val exitProgress: State<Float>,
) {
    fun exitMotion(key: String): Modifier {
        if (key !in leaving) return Modifier
        return Modifier
            .clearAndSetSemantics { hideFromAccessibility() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            .graphicsLayer {
                val progress = exitProgress.value
                translationX = (size.width + 32.dp.toPx()) * progress
                alpha = 1f - progress
            }
    }
}

@Composable
internal fun rememberDisplayedOrderCards(results: OrderCardResults, listState: LazyListState, idField: String): DisplayedOrderCards {
    val latest = remember(results, idField) { keyedOrderCards(results.rows, idField) }
    var settled by remember(listState, idField) { mutableStateOf(results) }
    var previous by remember(listState, idField) { mutableStateOf(latest) }
    var leaving by remember(listState, idField) { mutableStateOf(emptySet<String>()) }
    val exit = remember(listState, idField) { Animatable(0f) }
    val animate = results !== settled && results.canAnimateFrom(settled)
    LaunchedEffect(results, listState, idField) {
        if (animate && leaving.isEmpty()) {
            val removed = removedOrderCardKeys(previous, latest, results.lastPage)
            if (listState.layoutInfo.visibleItemsInfo.any { it.key in removed }) {
                exit.snapTo(0f)
                leaving = removed
                // Keep the exiting rows' slots until the slide finishes, then animateItem moves the rest.
                exit.animateTo(1f, tween(Motion.Fast, easing = Motion.Leave))
            }
        }
        previous = latest
        settled = results
        leaving = emptySet()
    }
    return DisplayedOrderCards(
        rows = if (animate) previous else latest,
        updating = results !== settled,
        leaving = if (animate) leaving else emptySet(),
        exitProgress = exit.asState(),
    )
}
