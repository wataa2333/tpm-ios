package com.personal.tpm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.SearchCondition
import com.personal.tpm.data.TaskSearchField

internal val taskSearchChoices = TaskSearchField.entries.map { SearchChoice(it.label, it.name) }

@Composable
internal fun SearchConditionsBar(
    choices: List<SearchChoice>,
    conditions: List<SearchCondition>,
    onChange: (List<SearchCondition>) -> Unit,
    modifier: Modifier = Modifier,
    searching: Boolean,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    AnimatedVisibility(
        visible = searching || conditions.isNotEmpty() || editing != null,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(Motion.Normal, easing = Motion.Arrive)) +
            fadeIn(tween(Motion.Fast)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(Motion.Normal, easing = Motion.Leave)) +
            fadeOut(tween(Motion.Fast)),
    ) {
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(enabled = conditions.size < choices.size, onClick = { editing = "" }) { Text("添加条件") }
        conditions.forEach { condition ->
            val label = choices.firstOrNull { it.key == condition.field }?.label ?: condition.field
            FilterChip(
                selected = true,
                onClick = { editing = condition.field },
                label = {
                    val operator = if (condition.exact) "等于" else "包含"
                    Text("$label $operator ${condition.value}", Modifier.widthIn(max = 200.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingIcon = {
                    IconButton(onClick = { onChange(conditions - condition) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "删除条件 $label", modifier = Modifier.size(16.dp))
                    }
                },
            )
        }
      }
    }
    val editKey = editing ?: return
    val original = conditions.firstOrNull { it.field == editKey }
    val available = choices.filter { choice -> choice.key == editKey || conditions.none { it.field == choice.key } }
    if (available.isEmpty()) return
    var field by rememberSaveable(editKey) { mutableStateOf(original?.field ?: available.first().key) }
    var value by rememberSaveable(editKey) { mutableStateOf(original?.value.orEmpty()) }
    var exact by rememberSaveable(editKey) { mutableStateOf(original?.exact ?: false) }
    AnimatedMessageDialog(
        visible = true,
        onDismissRequest = { editing = null },
        title = { Text(if (original == null) "添加条件" else "编辑条件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceGroup(
                    available.map { it.label }, available.indexOfFirst { it.key == field },
                    { field = available[it].key }, scrollable = true,
                )
                SingleChoiceGroup(listOf("包含", "等于"), if (exact) 1 else 0, { exact = it == 1 })
                TextEntry("搜索内容", value, { value = it }, limit = 100)
                if (original == null) {
                    Text("结果需同时满足所有条件", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = {
                val condition = SearchCondition(field, value.trim(), exact)
                onChange(if (original == null) conditions + condition else conditions.map { if (it == original) condition else it })
                editing = null
            }) { Text("应用") }
        },
        dismissButton = {
            if (original != null) TextButton(onClick = {
                onChange(conditions - original)
                editing = null
            }) { Text("删除") }
            TextButton(onClick = { editing = null }) { Text("取消") }
        },
    )
}
