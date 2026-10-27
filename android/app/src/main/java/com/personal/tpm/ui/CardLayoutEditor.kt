package com.personal.tpm.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex

/** The preview uses the actual list card renderer. Demo text never reaches any server request. */
@Composable
fun CardLayoutEditor(onBack: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(CardType.Fault) }
    var typesOpen by remember { mutableStateOf(false) }
    var expanded by remember(type) { mutableStateOf(false) }
    var selected by remember(type) { mutableStateOf<String?>(null) }
    var editingField by remember(type) { mutableStateOf(false) }
    var adding by remember(type) { mutableStateOf(false) }
    var dragging by remember(type) { mutableStateOf<String?>(null) }
    var dragOffset by remember(type) { mutableStateOf(Offset.Zero) }
    var origin by remember(type) { mutableStateOf(Offset.Zero) }
    val bounds = remember(type) { mutableMapOf<String, Rect>() }
    var pairing by remember(type) { mutableStateOf(false) }
    var cardBounds by remember(type) { mutableStateOf(Rect.Zero) }
    var compactBounds by remember(type) { mutableStateOf(Rect.Zero) }
    val dropMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    fun target(field: String, point: Offset): Int? {
        val snapped = snapCompactSlot(point, compactBounds, dropMargin) ?: return null
        if (field == DETAIL_BUTTON) return snapped
        val row = snapped / 2
        val anchor = bounds.entries.filter { (name, rect) ->
            name != field && compactBounds.contains(rect.center) &&
                (((rect.center.y - compactBounds.top) / compactBounds.height) * 3).toInt().coerceIn(0, 2) == row
        }.minByOrNull { it.value.left }?.value
        return row * 2 + if (anchor != null && point.x >= anchor.center.x) 1 else 0
    }
    // The gesture coroutine survives recomposition: always invoke the latest layout and bounds.
    val drop by rememberUpdatedState<(String, Offset) -> Unit> { field, point ->
        val snapped = target(field, point)
        if (snapped != null) CardPreferences.setSlot(type, field, snapped)
    }

    val edit = CardLayoutEditing({ field -> selected = field; editingField = true }, { compactBounds = it }, reserveRows = dragging != null) { field ->
        Modifier.onGloballyPositioned { if (dragging != field) bounds[field] = it.boundsInRoot() }
            .zIndex(if (dragging == field) 2f else 0f)
            .graphicsLayer { if (dragging == field) { translationX = dragOffset.x; translationY = dragOffset.y; alpha = .8f } }
            .border(1.dp, if (selected == field) accent else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable { selected = field; editingField = true }
            .pointerInput(type, field) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        selected = field; dragging = field; dragOffset = Offset.Zero
                        origin = (bounds[field]?.topLeft ?: Offset.Zero) + point
                    },
                    onDrag = { change, amount -> change.consume(); dragOffset += amount },
                    onDragCancel = { dragging = null; dragOffset = Offset.Zero },
                    onDragEnd = {
                        drop(field, origin + dragOffset)
                        dragging = null; dragOffset = Offset.Zero
                    },
                )
            }
    }
    Scaffold(contentWindowInsets = appContentInsets(), topBar = { AppTopBar("卡片自定义", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                TextButton(onClick = { typesOpen = true }) { AnimatedLabel("${type.title} ▾") }
                AppOptionSheet(typesOpen, { typesOpen = false }, title = "卡片类型") {
                    CardType.entries.forEach { item -> AppOptionItem(text = { AnimatedLabel(item.title) }, selected = type == item, onClick = { type = item; typesOpen = false }) }
                }
            }
            Text("最多四行：标题一行，下方三行。横向虚线为行边界；同行字段紧接排列，用“ · ”分隔。拖动调整行和先后，超出内容保留在详情。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedLabel(if (dragging != null) "松手吸附到高亮行 · 边界外 32dp 仍可吸附" else "长按字段或按钮拖动，目标行高亮后松手", style = MaterialTheme.typography.labelMedium, color = if (dragging != null) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            key(type) {
                OrderCard(remember(type) { previewCard(type) }, expanded, { expanded = !expanded },
                    Modifier.onGloballyPositioned { cardBounds = it.boundsInRoot() }.drawWithContent {
                        drawContent()
                        if (dragging != null) {
                            val slot = target(dragging!!, origin + dragOffset)
                            if (slot != null) {
                                val width = if (dragging == DETAIL_BUTTON) compactBounds.width / 2 else compactBounds.width
                                val height = compactBounds.height / 3
                                drawRect(accent.copy(alpha = .18f), Offset(compactBounds.left - cardBounds.left + (if (dragging == DETAIL_BUTTON) slot % 2 * width else 0f),
                                    compactBounds.top - cardBounds.top + slot / 2 * height), androidx.compose.ui.geometry.Size(width, height))
                            }
                        }
                    }, editing = edit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { adding = true }) { Text("＋ 添加字段") }
                TextButton(onClick = { expanded = !expanded }) { AnimatedLabel(if (expanded) "收起详情预览" else "展开详情预览") }
            }
            TextButton(onClick = { CardPreferences.reset(type); expanded = false; selected = null; bounds.clear() }) { Text("恢复此类型默认布局") }
            Text("修改自动保存到本机，列表卡片与骨架屏同步更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    AnimatedMessageDialog(visible = adding, onDismissRequest = { adding = false }, title = { Text("添加到缩略卡片") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp).clip(RoundedCornerShape(20.dp))) {
            val fields = CardPreferences.orderedFields(type).filter { CardPreferences.placement(type, it, CardPreferences.default(type, it)) != FieldPlace.Compact }
            if (fields.isEmpty()) item { Text("所有字段都已在缩略卡片中") }
            items(fields, key = { it }) { field ->
                ListItem(headlineContent = { AnimatedLabel(field) }, modifier = Modifier.clickable {
                    CardPreferences.set(type, field, FieldPlace.Compact); selected = field; adding = false
                })
            }
        } }, confirmButton = { TextButton(onClick = { adding = false }) { Text("完成") } })
    val field = selected
    if (field != null) AnimatedMessageDialog(visible = editingField, onDismissRequest = { editingField = false }, title = { AnimatedLabel(field) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (field == DETAIL_BUTTON) {
                val position = CardPreferences.detailButtonPosition(type)
                Text("按钮位置", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { CardPreferences.resetDetailButton(type) }) { Text("恢复默认 · 第二行右侧") }
                BlockSegmented(listOf("上方", "下方"), if (position.top) 0 else 1, { index ->
                    CardPreferences.setDetailButtonPosition(type, dropDetailButtonPosition(if (position.left) 0f else 1f, index.toFloat()))
                })
                BlockSegmented(listOf("靠左", "靠右"), if (position.left) 0 else 1, { index ->
                    CardPreferences.setDetailButtonPosition(type, dropDetailButtonPosition(index.toFloat(), if (position.top) 0f else 1f))
                })
            } else {
            val place = CardPreferences.placement(type, field, CardPreferences.default(type, field))
            BlockSegmented(FieldPlace.entries.map { it.title }, place.ordinal, { CardPreferences.set(type, field, FieldPlace.entries[it]) })
            Reveal(place != FieldPlace.Hidden) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val position = CardPreferences.position(type, field)
                TextButton(onClick = { pairing = true; editingField = false }) { Text("与另一个字段同行 ·") }
                Text("排版位置", style = MaterialTheme.typography.labelLarge)
                BlockSegmented(FieldPosition.entries.map { it.title }, position.ordinal, { CardPreferences.setPosition(type, field, FieldPosition.entries[it]) })
                if (place == FieldPlace.Compact) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示字段名称", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(CardPreferences.showLabel(type, field), { CardPreferences.setShowLabel(type, field, it) })
                }
            }
            }
            }
        } }, confirmButton = { TextButton(onClick = { editingField = false }) { Text("完成") } })
    if (field != null && field != DETAIL_BUTTON) AnimatedMessageDialog(visible = pairing, onDismissRequest = { pairing = false }, title = { AnimatedLabel("选择与「$field」同行的字段") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp).clip(RoundedCornerShape(20.dp))) {
            items(CardPreferences.orderedFields(type).filter { it != field && CardPreferences.placement(type, it, CardPreferences.default(type, it)) != FieldPlace.Hidden }, key = { it }) { target ->
                ListItem(headlineContent = { AnimatedLabel(target) }, supportingContent = { AnimatedLabel("$target · $field") }, modifier = Modifier.clickable {
                    CardPreferences.pair(type, field, target, true); pairing = false
                })
            }
        } }, confirmButton = { TextButton(onClick = { pairing = false }) { Text("取消") } })

}

