package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppOptionSheet(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String = "选择操作",
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    AppSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnimatedLabel(title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun AppOptionItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (selected) colors.onSecondaryContainer else colors.onSurface
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLowest)
        .semantics { this.selected = selected }
        .clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (leadingIcon != null) leadingIcon()
        Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = ink, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)) { text() } }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = ink)
    }
}
