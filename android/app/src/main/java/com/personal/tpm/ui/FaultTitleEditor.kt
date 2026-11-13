package com.personal.tpm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.FaultPartVariable
import com.personal.tpm.data.FaultTitlePattern
import com.personal.tpm.data.faultTitleVariable

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FaultTitleEditor(
    editor: TextFieldState,
    onVariable: (String, (String) -> Unit) -> Unit,
    enabled: Boolean = true,
) {
    fun insert(variable: String) {
        if (variable in FaultTitlePattern(editor.text.toString()).variables) return
        editor.edit {
            val start = selection.min
            val token = FaultTitlePattern.token(variable)
            replace(start, selection.max, token)
            selection = TextRange(start + token.length)
        }
    }

    GroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FaultTitleInput(editor, enabled)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("插入项目", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                listOf("机台号", FaultPartVariable, "自定义变量").forEach { name ->
                    FilledTonalButton(onClick = { onVariable(name, ::insert) }, enabled = enabled, colors = colors,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("$name ▾", maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val deleteTitleCapsule = InputTransformation {
    if (changes.changeCount != 1 || !changes.getRange(0).collapsed) return@InputTransformation
    val deleted = changes.getOriginalRange(0)
    val tokens = faultTitleVariable.findAll(originalText).filter {
        it.range.first < deleted.max && it.range.last >= deleted.min
    }.toList()
    if (tokens.isEmpty()) return@InputTransformation
    val start = minOf(deleted.min, tokens.first().range.first)
    val end = maxOf(deleted.max, tokens.last().range.last + 1) - deleted.length
    if (start < end) replace(start, end, "")
    selection = TextRange(start)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaultTitleInput(editor: TextFieldState, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val tokenStyle = SpanStyle(color = colors.onSecondaryContainer, fontSize = typography.labelMedium.fontSize,
        fontWeight = FontWeight.Medium)
    val transformation = remember(tokenStyle) {
        OutputTransformation {
            val tokens = faultTitleVariable.findAll(asCharSequence().toString()).toList()
            for (token in tokens) {
                // Replace the whole token as one unit. The inner padding is display-only.
                replace(token.range.first, token.range.last + 1, "\u2002${token.groupValues[1]}\u2002")
            }
            for (token in tokens) addStyle(tokenStyle, token.range.first, token.range.last + 1)
        }
    }
    var getTextLayout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("报修名称格式", style = typography.labelMedium, color = colors.onSurfaceVariant)
        BasicTextField(
            state = editor,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "报修名称格式" },
            textStyle = typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            inputTransformation = deleteTitleCapsule,
            outputTransformation = transformation,
            onTextLayout = { getTextLayout = it },
            decorator = { input ->
                Box(Modifier.fillMaxWidth().drawBehind {
                    val layout = getTextLayout?.invoke() ?: return@drawBehind
                    for (token in layout.layoutInput.text.spanStyles.filter { it.item == tokenStyle }) {
                        val firstLine = layout.getLineForOffset(token.start)
                        val lastLine = layout.getLineForOffset(token.end - 1)
                        for (line in firstLine..lastLine) {
                            val start = maxOf(token.start, layout.getLineStart(line))
                            val end = minOf(token.end, layout.getLineEnd(line))
                            if (start >= end) continue
                            val bounds = layout.getPathForRange(start, end).getBounds()
                            val sideInset = 1.5.dp.toPx()
                            val height = minOf(bounds.height, (tokenStyle.fontSize.toPx() + 8.dp.toPx()))
                            if (bounds.width <= 2 * sideInset || height <= 0f) continue
                            drawRoundRect(
                                color = colors.secondaryContainer,
                                topLeft = Offset(bounds.left + sideInset, bounds.center.y - height / 2),
                                size = Size(bounds.width - 2 * sideInset, height),
                                cornerRadius = CornerRadius(height / 2),
                            )
                        }
                    }
                }) {
                    if (editor.text.isEmpty()) {
                        Text("例：配件维修 【机台号】 【配件项目】", style = typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    input()
                }
            },
        )
    }
}
