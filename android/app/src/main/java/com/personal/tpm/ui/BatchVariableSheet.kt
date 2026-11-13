package com.personal.tpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.BatchFaultRow
import com.personal.tpm.data.FaultPartVariable
import com.personal.tpm.data.FaultTitleAssetField
import com.personal.tpm.data.FaultTitleLimit
import com.personal.tpm.data.faultVariableOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchVariableSheet(
    batch: BatchFaultForm, mode: String, onDismiss: () -> Unit,
    onInsert: (String) -> Unit, onSelectAssets: () -> Unit,
) {
    val machines = mode == FaultTitleAssetField.Machine.label
    val parts = mode == FaultPartVariable
    val custom = batch.pattern?.customVariables.orEmpty().filterNot { it == FaultPartVariable }
    var variable by remember(mode) { mutableStateOf(if (parts) FaultPartVariable else custom.firstOrNull().orEmpty()) }
    var expandedRow by remember { mutableStateOf<String?>(null) }
    var optionEditor by remember { mutableStateOf<String?>(null) }
    var showingSavedRules by remember { mutableStateOf(false) }
    val options = batch.optionsFor(variable)
    val formatVariable = if (machines) mode else variable
    val inserted = formatVariable in batch.pattern?.variables.orEmpty()
    val colors = MaterialTheme.colorScheme
    val accent = accentInk()
    val ordersByAsset = batch.rows.groupBy { it.assetId }
    val displayRows = if (machines || parts) ordersByAsset.values.map { it.first() } else batch.rows

    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mode, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            if (!machines && !parts && custom.size > 1) {
                SingleChoiceGroup(custom, custom.indexOf(variable), { variable = custom[it]; expandedRow = null }, scrollable = true)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (machines) {
                    InsertBatchProjectButton(inserted, Modifier.weight(1f)) { onInsert(mode) }
                    OutlinedButton(onClick = onSelectAssets, modifier = Modifier.weight(1f)) { Text("选择资产") }
                } else {
                    if (!parts) FilledTonalButton(onClick = { optionEditor = "" }, modifier = Modifier.weight(1f)) { Text("+ 新建变量") }
                    if (variable.isNotBlank()) OutlinedButton(onClick = { optionEditor = variable }, modifier = Modifier.weight(1f)) {
                        Text(if (options.isEmpty()) "添加子项" else "编辑子项")
                    }
                    if (parts) InsertBatchProjectButton(inserted, Modifier.weight(1f)) { onInsert(variable) }
                }
            }
            if (parts) Text("每台可选多个配件。点「插入项目」把配件加入报修名称。", Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("机台号", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(if (machines) "资产编码" else variable, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            HorizontalDivider()
            ScrollableLazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (displayRows.isEmpty()) item {
                    Text("选择报修资产后，可在这里查看机台和选择子项。", Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                itemsIndexed(displayRows, key = { _, row -> if (machines || parts) row.assetId else row.id }) { index, row ->
                    if (parts) {
                        val orders = ordersByAsset.getValue(row.assetId)
                        val expanded = expandedRow == row.assetId
                        PartSelectionRow(orders, options, expanded,
                            onExpand = { expandedRow = if (expanded) null else row.assetId },
                            onToggle = { option -> batch.togglePart(row.assetId, option) })
                        HorizontalDivider(color = colors.outlineVariant)
                        return@itemsIndexed
                    }
                    val canChoose = !machines && variable.isNotBlank() && row.canSubmit
                    val value = row.titleValues[variable].orEmpty()
                    val expanded = canChoose && expandedRow == row.id
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .background(if (expanded) colors.surfaceContainer else colors.surfaceContainerLowest)
                            .then(if (canChoose) Modifier.clickable { expandedRow = if (expandedRow == row.id) null else row.id } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column {
                                Text(row.displayCode, style = MaterialTheme.typography.titleSmall)
                                if (!machines && ordersByAsset.getValue(row.assetId).size > 1) {
                                    Text(row.titleValues[FaultPartVariable].orEmpty(), style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                            Text(
                                if (machines) row.asset["assetCode"].text else value.ifBlank { if (canChoose) "请选择" else "—" },
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (canChoose) accent else colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            )
                            if (canChoose) BatchDisclosure(expanded, "子项", accent)
                        }
                        Reveal(expanded) {
                            Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow).selectableGroup()) {
                                if (options.isEmpty()) TextButton(onClick = { optionEditor = variable }) { Text("添加子项") }
                                options.forEach { option ->
                                    val selected = value == option
                                    HorizontalDivider(color = colors.outlineVariant)
                                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                        .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
                                        .selectable(selected, role = Role.RadioButton) {
                                            if (!inserted) onInsert(variable)
                                            batch.setTitleValues(row, mapOf(variable to option))
                                            expandedRow = null
                                        }.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        RadioButton(selected, onClick = null, modifier = Modifier.size(20.dp),
                                            colors = RadioButtonDefaults.colors(selectedColor = colors.onSecondaryContainer))
                                        Text(option, style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                        if (index < displayRows.lastIndex) HorizontalDivider(color = colors.outlineVariant)
                    }
                }
                if (machines && batch.rules.isNotEmpty()) item {
                    TextButton(onClick = { showingSavedRules = !showingSavedRules }) { Text("草稿中的已有设置") }
                    if (showingSavedRules) batch.rules.forEach { rule ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${rule.variable} = ${rule.value} · ${rule.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { batch.removeRule(rule) }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
    optionEditor?.let { name ->
        BatchVariableOptionsDialog(name, batch.pattern?.variables.orEmpty(), batch.optionsFor(name),
            onDismiss = { optionEditor = null }) { savedName, entries ->
            batch.setOptions(savedName, entries)
            if (name.isEmpty()) { onInsert(savedName); variable = savedName }
            optionEditor = null
        }
    }
}

@Composable
private fun PartSelectionRow(
    orders: List<BatchFaultRow>, options: List<String>, expanded: Boolean,
    onExpand: () -> Unit, onToggle: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = accentInk()
    val selected = orders.mapNotNull { it.titleValues[FaultPartVariable]?.takeIf(String::isNotBlank) }.toSet()
    val locked = orders.filterNot { it.canSubmit }.mapNotNull { it.titleValues[FaultPartVariable] }.toSet()
    val canChoose = orders.any { it.canSubmit }
    val optionShape = MaterialTheme.shapes.medium

    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onExpand).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(orders.first().displayCode, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val summary = selected.joinToString("、")
                AnimatedPartLabel(summary, "selected parts") { value ->
                    if (value.isNotEmpty()) Text(value, Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            val selectionLabel = if (selected.isNotEmpty()) "已选 ${selected.size} 项" else if (canChoose) "选配件" else "未选配件"
            AnimatedPartLabel(selectionLabel, "part count") { value ->
                Text(value, style = MaterialTheme.typography.labelMedium, color = if (canChoose) accent else colors.onSurfaceVariant)
            }
            BatchDisclosure(expanded, "配件", accent)
        }
        Reveal(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (options + selected).distinct().chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { option ->
                            val checked = option in selected
                            val enabled = canChoose && option !in locked
                            val fill by animateColorAsState(if (checked) colors.secondaryContainer else colors.surfaceContainerLowest,
                                tween(Motion.Normal, easing = Motion.Change), label = "part fill")
                            val outline by animateColorAsState(if (checked && enabled) accent else colors.outlineVariant,
                                tween(Motion.Normal, easing = Motion.Change), label = "part outline")
                            val checkAlpha by animateFloatAsState(if (checked) 1f else 0f,
                                tween(Motion.Instant, easing = Motion.Change), label = "part check")
                            Row(Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp)
                                .clip(optionShape)
                                .background(fill)
                                .border(1.dp, outline, optionShape)
                                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(option) })
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(option, style = MaterialTheme.typography.bodyMedium,
                                    color = if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.Check, contentDescription = null,
                                    modifier = Modifier.size(16.dp).graphicsLayer { alpha = checkAlpha }, tint = accent)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedPartLabel(text: String, label: String, content: @Composable (String) -> Unit) {
    AnimatedContent(text, label = label, transitionSpec = {
        (slideInVertically(tween(Motion.Normal, easing = Motion.Arrive)) { it / 3 } + fadeIn(tween(Motion.Normal)))
            .togetherWith(slideOutVertically(tween(Motion.Fast, easing = Motion.Leave)) { -it / 3 } + fadeOut(tween(Motion.Fast)))
            .using(SizeTransform { _, _ -> tween(Motion.Normal, easing = Motion.Change) })
    }) { value -> content(value) }
}

@Composable
private fun BatchDisclosure(expanded: Boolean, label: String, tint: androidx.compose.ui.graphics.Color) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.Normal, easing = Motion.Change), label = "batch disclosure")
    Icon(Icons.Filled.ExpandMore, if (expanded) "收起$label" else "展开$label",
        Modifier.size(18.dp).graphicsLayer { rotationZ = angle }, tint = tint)
}

@Composable
private fun InsertBatchProjectButton(inserted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier, enabled = !inserted) {
        if (inserted) Icon(Icons.Filled.Check, "已插入", Modifier.padding(end = 4.dp).size(16.dp))
        Text(if (inserted) "已插入" else "插入项目")
    }
}

@Composable
private fun BatchVariableOptionsDialog(
    name: String, existingNames: List<String>, options: List<String>, onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var typedName by remember { mutableStateOf(name) }
    var values by remember { mutableStateOf(options.joinToString("\n")) }
    val key = typedName.trim()
    val entries = faultVariableOptions(values)
    val validName = name.isNotEmpty() || (key.isNotEmpty() && key.none { it in "【】\t\r\n" } &&
        key !in existingNames && key != FaultPartVariable && FaultTitleAssetField.entries.none { it.label == key })
    AnimatedMessageDialog(
        visible = true, onDismissRequest = onDismiss,
        title = { Text(if (name.isEmpty()) "新建自定义变量" else "编辑「$name」子项") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (name.isEmpty()) TextEntry("变量名称", typedName, { typedName = it }, limit = 24, placeholder = "例：处理方式")
                TextEntry("子项 · 每行一项", values, { values = it }, limit = 4000, minLines = 2, placeholder = "例：更换\n修复")
            }
        },
        confirmButton = {
            TextButton(enabled = validName && entries.isNotEmpty() && entries.all { it.length <= FaultTitleLimit },
                onClick = { onSave(key, entries) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
