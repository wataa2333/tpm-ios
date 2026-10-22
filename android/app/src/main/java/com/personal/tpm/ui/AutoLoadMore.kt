package com.personal.tpm.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observe visible items, not lazy precomposition: request the next page only when its footer enters the viewport. */
@Composable
fun AutoLoadMore(state: LazyListState, rowCount: Int, enabled: Boolean, load: () -> Unit) {
    LaunchedEffect(state, rowCount, enabled) {
        if (!enabled || rowCount == 0) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
        }.distinctUntilChanged().collect { bottom -> if (bottom) load() }
    }
}
