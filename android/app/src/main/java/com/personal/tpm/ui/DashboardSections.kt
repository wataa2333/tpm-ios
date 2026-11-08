package com.personal.tpm.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer

fun normalizedSectionOrder(saved: List<String>, defaults: List<String>) = (saved.filter { it in defaults } + defaults).distinct()

class DashboardLayout(context: Context, private val page: String, defaults: List<String>) {
    private val prefs = context.getSharedPreferences("dashboard_sections", Context.MODE_PRIVATE)
    var order by mutableStateOf(normalizedSectionOrder(prefs.getString("$page.order", "").orEmpty().split(','), defaults)); private set
    var collapsed by mutableStateOf(prefs.getStringSet("$page.collapsed", emptySet()).orEmpty().toSet()); private set
    fun toggle(id: String) { collapsed = if (id in collapsed) collapsed - id else collapsed + id; prefs.edit().putStringSet("$page.collapsed", collapsed).apply() }
    fun move(id: String, step: Int) {
        val index = order.indexOf(id); val target = index + step
        if (index < 0 || target !in order.indices) return
        order = order.toMutableList().apply { removeAt(index); add(target, id) }
        prefs.edit().putString("$page.order", order.joinToString(",")).apply()
    }
}

@Composable
fun rememberDashboardLayout(page: String, defaults: List<String>): DashboardLayout {
    val context = LocalContext.current
    return remember(page) { DashboardLayout(context.applicationContext, page, defaults) }
}

@Composable
fun DashboardSectionHeader(layout: DashboardLayout, id: String, title: String) {
    var menu by remember { mutableStateOf(false) }
    val folded = id in layout.collapsed
    val angle by androidx.compose.animation.core.animateFloatAsState(if (folded) -90f else 0f, androidx.compose.animation.core.tween(Motion.Normal), label = "section disclosure")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { layout.toggle(id) }.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { layout.toggle(id) }) {
            Icon(Icons.Default.ExpandMore, if (folded) "展开$title" else "收起$title", Modifier.size(20.dp).graphicsLayer { rotationZ = angle })
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "调整${title}顺序") }
    }
    AppOptionSheet(expanded = menu, onDismissRequest = { menu = false }, title = title) {
        AppOptionItem(text = { Text(if (folded) "展开" else "折叠") }, onClick = { menu = false; layout.toggle(id) })
        if (layout.order.indexOf(id) > 0) AppOptionItem(text = { Text("上移") }, onClick = { menu = false; layout.move(id, -1) })
        if (layout.order.indexOf(id) < layout.order.lastIndex) AppOptionItem(text = { Text("下移") }, onClick = { menu = false; layout.move(id, 1) })
    }
}

/** Keep the section in the lazy list until its exit finishes; retain input across folds. */
@Composable
fun DashboardSection(layout: DashboardLayout, id: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    val savedState = rememberSaveableStateHolder()
    Column(Modifier.fillMaxWidth()) {
        DashboardSectionHeader(layout, id, title)
        AnimatedVisibility(id !in layout.collapsed,
            enter = expandVertically(tween(Motion.Normal, easing = Motion.Change), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(Motion.Normal, easing = Motion.Change), shrinkTowards = Alignment.Top)) {
            savedState.SaveableStateProvider("body") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

/** A small, non-scrolling adaptive grid inside a single animated section. */
@Composable
fun <T> DashboardTiles(values: List<T>, itemKey: (T) -> Any, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 12.dp) / (150.dp + 12.dp)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            values.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { value -> key(itemKey(value)) { Box(Modifier.weight(1f)) { content(value) } } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
