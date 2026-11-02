package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.Jv
import com.personal.tpm.data.Session

val LocalSession = compositionLocalOf<Session> { error("Session not provided") }
val LocalVault = compositionLocalOf<AccountVault> { error("AccountVault not provided") }
val LocalPickerPrefs = compositionLocalOf<com.personal.tpm.data.PickerPrefs> { error("PickerPrefs not provided") }
val LocalDrafts = compositionLocalOf<com.personal.tpm.data.DraftStore> { error("DraftStore not provided") }

/** A plain message dialog. */
@Composable
fun ErrorDialog(title: String, message: String, onClose: () -> Unit) {
    GlassAlertDialog(
        onDismissRequest = onClose,
        title = { AnimatedLabel(title) },
        text = { AnimatedLabel(message) },
        confirmButton = { TextButton(onClick = onClose) { Text("好") } },

    )
}

/** A rounded white card holding a group of rows: the basic building block of the pages. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(Modifier.padding(vertical = 8.dp)) { content() } }
}

/**
 * One label and its value, long text wrapping. A page wraps all its rows in ONE SelectionContainer (long-press any value to copy it):
 * a selection container per row is what made the detail pages drop frames when they opened.
 */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Row(modifier.fillMaxWidth().padding(horizontal = inset, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        AnimatedLabel(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedLabel(value.ifEmpty { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The row an order was opened from: the detail screen starts with it on screen at once (no empty page while the full record loads) and
 * fills in the rest when the answer comes.
 */
object OrderPreview {
    private val rows = HashMap<String, Jv>()
    fun put(kind: String, id: String, row: Jv) { if (id.isNotEmpty()) rows["$kind|$id"] = row }
    fun get(kind: String, id: String): Jv? = rows["$kind|$id"]
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)

/** The rounded search box of every list: a search icon, a clear button, and the keyboard's search key. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextField(
        value = value, onValueChange = onValueChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        placeholder = { AnimatedLabel(placeholder) },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) GlassIconButton(onClick = { onValueChange("") }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "清空")
            }
        },
        shape = androidx.compose.foundation.shape.CircleShape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
    )
}


// ---- block-style controls: filled shapes, no outlines ----

/** The quiet button (was the outlined one): a filled soft block with dark text. [contentColor] for a warning (red) one. */
@Composable
fun SoftButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    if (AppPreferences.liquidGlass) {
        GlassButton(onClick, modifier, enabled, contentColor = contentColor, content = content)
        return
    }
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = contentColor),
        content = content,
    )
}

/** The asset shortcut shares the quiet action style and reserves a full line for its long label. */
@Composable
fun AssetReportButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SoftButton(onClick = onClick, enabled = enabled, modifier = modifier.then(Modifier.heightIn(min = BarSpec.ButtonHeight))) {
        androidx.compose.material3.Text("使用此资产编码进行新建报修", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A small filled capsule with a label and an optional trailing icon (a menu opener, a removable filter); [selected] tints it with the theme colour. */
@Composable
fun BlockChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val fill = if (selected) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.colorScheme.primary, 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.layout.Row(
        modifier.clip(androidx.compose.foundation.shape.CircleShape).then(if (AppPreferences.liquidGlass) Modifier.glassSurface(backdrop = null, shape = androidx.compose.foundation.shape.CircleShape, tint = fill, prominent = selected) else Modifier.background(fill)).clickable(onClick = onClick).padding(start = 14.dp, end = if (trailing != null) 8.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        // The same ink as the capsules next to it (the second level of the text tones), also for the icon.
        val ink = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) {
            AnimatedLabel(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, softWrap = false)
            trailing?.invoke()
        }
    }
}

/** A row of equal blocks to choose one from (was the outlined segmented row): the chosen one is filled with the theme colour. */
@Composable
fun BlockSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, name ->
            val on = index == selected
            val fill by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), label = "segFill")
            val ink by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), label = "segInk")
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).then(if (AppPreferences.liquidGlass) Modifier.glassSurface(backdrop = null, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), tint = fill, prominent = on) else Modifier.background(fill)).clickable { onSelect(index) }.padding(vertical = 10.dp),
                androidx.compose.ui.Alignment.Center,
            ) { AnimatedLabel(name, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1) }
        }
    }
}

/**
 * Colours of a text field that is a filled block (no outline, no underline). A field lying on the grey page is white (a grey block on the grey page
 * could not be seen); a field inside a white card ([onCard]) is the light grey of the tones.
 */
@Composable
fun blockFieldColors(onCard: Boolean = false): androidx.compose.material3.TextFieldColors {
    val fill = if (onCard) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    return androidx.compose.material3.TextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill, errorContainerColor = fill,
        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    )
}


/**
 * The one measure of the bar of buttons at the bottom of a page (an order's 退单 / next step, a form's 保存 / 提交, the single 提交 of a step, the
 * batch bar): the same button height, the same space around them and between them, and buttons of equal width, so the bars of two pages look
 * the same side by side (they did not, as one page slid in over another).
 */
object BarSpec {
    val ButtonHeight = 48.dp
    val Horizontal = 16.dp
    val Vertical = 12.dp
    val Gap = 12.dp
}


/** The theme colour as the colour of a text (an entered value, an action word): on a light page it is darkened until it reads, on a dark page it is as it is. */
@Composable
fun accentInk(): androidx.compose.ui.graphics.Color {
    val primary = MaterialTheme.colorScheme.primary
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) primary else com.personal.tpm.ui.theme.ThemeConfig.readable(primary)
}


/** The developer's contact, shown under the sign-in page, the key page and 我的. */
const val DEVELOPER_CONTACT = "wataa2333@gmail.com"

/** The version of the app, and under it the developer's contact (as text, on its own line). */
@Composable
fun AppFooter(showVersion: Boolean = true, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "版本 " + info.versionName + "（" + (if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()) + "）"
        }.getOrDefault("")
    }
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showVersion && version.isNotEmpty()) AnimatedLabel(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        AnimatedLabel("开发者联系方式：$DEVELOPER_CONTACT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
