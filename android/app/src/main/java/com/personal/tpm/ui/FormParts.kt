package com.personal.tpm.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.allowsApproverDefault
import com.personal.tpm.data.isChosenApprover
import com.personal.tpm.data.isCountersign
import com.personal.tpm.data.parsedDate
import com.personal.tpm.data.serverDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// The building blocks of the forms: a row that opens a selection sheet, a date-and-time row, a text box with its counter, the photo picker with
// upload, and the bar of 保存 / 提交 at the bottom.

/**
 * A choice among a few, drawn like the status capsules: the chosen one is filled with the theme colour and shows a tick that opens out beside the
 * word, the others are soft grey; the fill, the colour of the word and the width all change smoothly when the choice moves.
 */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean = true) {
    val background by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), label = "chipFill")
    val content by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), label = "chipText")
    Row(
        Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if(enabled) background else background.copy(alpha=.38f)).clickable(enabled=enabled,onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), expandFrom = Alignment.Start) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), shrinkTowards = Alignment.Start) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Fast, easing = Motion.Change)),
        ) { Icon(Icons.Filled.Check, contentDescription = null, Modifier.padding(end = 6.dp).size(16.dp), tint = content) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if(enabled) content else content.copy(alpha=.38f), maxLines = 1, softWrap = false)
    }
}

/** The small heading above a group of fields on the form and process pages. */
@Composable
fun FormTitle(text: String) {
    // A small rounded bar of the theme colour and the title: the groups of a page are set apart by this mark, not by a block (a block in the page's
    // own grey could not be seen, and its rounded corner cut the bar in half).
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Text(text, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "名称 ＊" with the star in red. */
@Composable
fun requiredLabel(label: String, required: Boolean) = buildAnnotatedString {
    append(label)
    if (required) withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(" ＊") }
}

/** A row that opens a selection sheet: the label, what is chosen (or "请选择"), an arrow. */
@Composable
fun PickRow(label: String, value: String, required: Boolean, onClick: () -> Unit) {
    // The label keeps its own width on one line (it used to share the row and wrap); what is chosen takes the rest and may take two lines.
    // The press feedback is a rounded shape inside the card (the clip comes before the click), not a rectangle that squares off the card's corners.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(requiredLabel(label, required), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
        Text(
            value.ifEmpty { "请选择" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Same content edges as PickRow, without pretending a read-only value is editable. */
@Composable
fun ReadOnlyFormRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .padding(start = 14.dp, end = 28.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A text box with a label, a counter ("12/255 字符") and a limit. */
@Composable
fun TextEntry(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, required: Boolean = false, limit: Int = 255,
    minLines: Int = 1, maxLines: Int = if (minLines > 1) 8 else 1, keyboardType: KeyboardType = KeyboardType.Text, placeholder: String = "",
) {
    // A white block on the grey page; inside it the label, then the field in a tinted box of its own, so it is plain where to type. The box is one
    // line high while empty and grows with what is typed (up to [maxLines]); it does not start as a big empty block.
    val multiline = minLines > 1
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(requiredLabel(label, required), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.TextField(
                value = value, onValueChange = { onChange(it.take(limit)) },
                modifier = Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.tween(Motion.Fast, easing = Motion.Change)),
                shape = RoundedCornerShape(8.dp),
                placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
                minLines = 1, maxLines = if (multiline) maxOf(maxLines, 8) else 1, singleLine = !multiline,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (multiline) Text(
                "${value.length}/$limit 字符", Modifier.fillMaxWidth().padding(end = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** Date, then time, as the two native pickers; the value is the server's "yyyy-MM-dd HH:mm" (or "yyyy-MM-dd" when [dateOnly]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(label: String, value: String, dateOnly: Boolean, required: Boolean, onChange: (String) -> Unit) {
    var step by remember { mutableStateOf(0) } // 0 closed, 1 date, 2 time
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val current = remember(value) { parsedDate(Jv(value)).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() }
    PickRow(label, value, required) { step = 1 }
    if (step == 1) {
        val state = rememberDatePickerState(initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    val day = Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    if (dateOnly) { onChange(day.toString()); step = 0 } else { pickedDate = day; step = 2 }
                }) { Text(if (dateOnly) "确定" else "下一步") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("取消") } },
        ) { DatePicker(state) }
    }
    if (step == 2) {
        val time = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text("选择时间") },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(onClick = {
                    val moment = LocalDateTime.of(pickedDate ?: LocalDate.now(), LocalTime.of(time.hour, time.minute))
                    onChange(serverDate(java.util.Date.from(moment.atZone(ZoneId.systemDefault()).toInstant()))); step = 0
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("取消") } },
        )
    }
}

private fun jpegOf(context: android.content.Context, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/** The pictures of a form (at most nine): each can be deleted, new ones are picked with the system photo picker and uploaded at once. */
@Composable
fun PhotosField(albums: Jv, onChange: (JSONArray) -> Unit, directory: String, onUploading: (Boolean) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val list = albums.array
    // The plain "pick several pictures" request (the system picker or the gallery, whichever the phone offers): the newer photo-picker request
    // checks limits that differ between phones and could throw the moment it was launched.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val chosen = uris.take(maxOf(0, 9 - list.size))
        if (chosen.isEmpty()) return@rememberLauncherForActivityResult
        uploading = true; onUploading(true)
        scope.launch {
            try {
                val next = JSONArray().also { a -> list.forEach { a.put(it.raw) } }
                for (uri in chosen) {
                    val bytes = withContext(Dispatchers.IO) { jpegOf(context, uri) } ?: throw IllegalStateException("无法读取照片")
                    next.put(session.uploadPhoto(bytes, directory).raw)
                    onChange(JSONArray(next.toString()))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "照片上传失败") }
            finally { uploading = false; onUploading(false) }
        }
    }
    androidx.compose.foundation.layout.Column(modifier) {
        list.forEachIndexed { index, image ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Image, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(image["name"].text.ifEmpty { "照片 ${index + 1}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onChange(JSONArray().also { a -> list.forEachIndexed { i, v -> if (i != index) a.put(v.raw) } }) }) { Icon(Icons.Filled.Delete, contentDescription = "删除照片", tint = MaterialTheme.colorScheme.error) }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !uploading && list.size < 9) {
                try { picker.launch("image/*") }
                catch (e: Exception) { onError("打不开相册：" + (e.message ?: "没有可用的相册应用")) }
            }.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (uploading) "正在上传照片…" else "选择现场照片（${list.size}/9）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The bar at the bottom of a form: 保存 (to the draft box on this phone: nothing is sent), a button that puts the keyboard away when it is up
 * (between the two), and 提交 (to the server). The bar rides above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormActionBar(busy: Boolean, enabled: Boolean, onSave: (() -> Unit)?, onSubmit: () -> Unit, submitLabel: String = "提交") {
    Surface(Modifier.fadeAbove(MaterialTheme.colorScheme.background), color = MaterialTheme.colorScheme.background, shadowElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical),
            horizontalArrangement = Arrangement.spacedBy(BarSpec.Gap), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSave != null) SoftButton(onClick = onSave, enabled = enabled && !busy, modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight)) { Text("保存") }
            Button(onClick = onSubmit, enabled = enabled && !busy, modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight)) { Text(if (busy) "请稍候…" else submitLabel) }
        }
    }
}

// ---- selection sheets opened from a form ----

/** What a form row asks the selection sheet for; the sheet is shown by [PickHost]. */
class PickRequest(val source: RefSource, val onPick: (Jv) -> Unit, val defaultField: String? = null, val multiple: Boolean = false, val selected: Set<String> = emptySet(), val onRemove: ((Jv) -> Unit)? = null)

@Composable
fun PickHost(request: PickRequest?, onDismiss: () -> Unit) {
    if (request != null) ReferenceSheet(request.source, request.selected, request.multiple, request.defaultField, onToggle = request.onPick, onDismiss = onDismiss, onRemove = request.onRemove)
}

/** The approver rows of the next step: one row per step the person has to name. */
@Composable
fun AssigneeRows(
    nodes: List<Jv>, selections: Map<String, List<String>>, defaultPrefix: String?,
    update: (String, (List<String>) -> List<String>) -> Unit, pick: (PickRequest) -> Unit,
) {
    val chosen = nodes.filter { isChosenApprover(it) }
    chosen.forEachIndexed { index, node ->
        val key = node["variableName"].text
        val names = selections[key].orEmpty()
        val multiple = isCountersign(node) || node["assigneeMultiple"].bool
        PickRow(node["taskName"].text.ifEmpty { "审批人" }, names.joinToString("、") { UserNames.show(it) }, true) {
            pick(PickRequest(
                RefSource.Users,
                onPick = { user ->
                    UserNames.remember(user)
                    val name = user["userName"].text
                    update(key) { current -> if (multiple) (if (name in current) current - name else current + name) else listOf(name) }
                },
                defaultField = if (allowsApproverDefault(node)) defaultPrefix?.let { "$it.$key" } else null,
                multiple = multiple, selected = names.toSet(),
            ))
        }
    }
}

/** The page of a form: top bar with a back arrow (closes it), the scrolling body in the rounded sheet, the bar of buttons at the bottom. */
@Composable
fun FormPage(
    title: String, busy: Boolean, onClose: () -> Unit, bottomBar: @Composable () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, snackbar: androidx.compose.material3.SnackbarHostState? = null,
    /** Whether the system back gesture may leave the page (not while it is saving or uploading). */
    backEnabled: Boolean = true,
    /** Whether the page must ask something before it leaves (then the back key goes to [onBackGesture] and not to the navigation). */
    interceptBack: Boolean = false,
    /** What the completed back gesture does; true when the page is going away, false when it stays (a question was asked instead). */
    onBackGesture: () -> Boolean = { onClose(); true },
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val session = LocalSession.current
    LaunchedEffect(Unit) { UserNames.ensure(session) }
    // Going back is left to the navigation host: the gesture slides the page out to the right with the finger and brings the page under it in,
    // like every other page of the app. The form takes the back key itself only when it has to ask or to refuse: while it is saving or uploading
    // (nothing happens), and when entered content is saved locally before closing.
    androidx.activity.compose.BackHandler(enabled = !backEnabled) { }
    androidx.activity.compose.BackHandler(enabled = backEnabled && interceptBack) { onBackGesture() }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    CompactTheme { androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = title, onBack = onClose, loading = busy, actions = actions) },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            RoundedSheet {
                PageWidth {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxSize().elastic().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { content() }
                }
            }
        }
    }
}
}
}

/** The drafts of this account and factory, newest first; a tap hands one over, the bin removes it. */
@Composable
fun DraftList(scopeKey: String, target: com.personal.tpm.data.DraftTarget, onPick: (com.personal.tpm.data.FormDraft) -> Unit) {
    val drafts = LocalDrafts.current
    var list by remember { mutableStateOf(drafts.drafts(scopeKey, target)) }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        if (list.isEmpty()) androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("草稿箱是空的", style = MaterialTheme.typography.titleMedium)
            Text("表单里点“保存”，或关闭时自动保留草稿，没填完的内容就会存在这里。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        list.forEach { draft ->
            androidx.compose.material3.ListItem(
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(draft) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(draft.title) },
                supportingContent = { Text(draft.subtitle, maxLines = 2) },
                trailingContent = {
                    IconButton(onClick = { drafts.remove(draft.id, scopeKey); list = drafts.drafts(scopeKey, target) }) { Icon(Icons.Filled.Delete, contentDescription = "删除草稿", tint = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftSheet(scopeKey: String, target: com.personal.tpm.data.DraftTarget, onPick: (com.personal.tpm.data.FormDraft) -> Unit, onDismiss: () -> Unit) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Text("草稿箱", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.layout.Column(Modifier.padding(bottom = 24.dp)) { DraftList(scopeKey, target, onPick) }
    }
}


// ---- one size of type, number rows, the slide-up page, real names of people ----

/** The form and process pages use a smaller, single scale of type: the same size for the same kind of text all the way down the page. */
@Composable
fun CompactTheme(content: @Composable () -> Unit) {
    // No longer a smaller scale: these pages use the same Material 3 type as the lists.
    content()
}

/** A row with a number (or short text) typed at its right end, no box around it: "维修花费 [ 0 ]". */
@Composable
fun InputRow(
    label: String, value: String, onChange: (String) -> Unit, required: Boolean = false, suffix: String = "", keyboardType: KeyboardType = KeyboardType.Decimal,
    limit: Int = 64, placeholder: String = "请输入", maximum: Double? = null,
) {
    val style = MaterialTheme.typography.bodyMedium
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var wasFocused by remember { mutableStateOf(false) }
    fun confirmQuantity() {
        val number = value.toDoubleOrNull()
        if (maximum != null && maximum.isFinite() && maximum >= 0 && number != null && number.isFinite() && number > maximum)
            onChange(com.personal.tpm.data.quantityText(maximum))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(requiredLabel(label, required), style = style, maxLines = 1, softWrap = false)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = { onChange(it.take(limit)) }, singleLine = true, modifier = Modifier.weight(1f).onFocusChanged {
                if (wasFocused && !it.isFocused) confirmQuantity()
                wasFocused = it.isFocused
            },
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { confirmQuantity(); focus.clearFocus() }),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
            },
        )
        if (suffix.isNotEmpty()) Text(suffix, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A page laid over the screen that rises from the bottom (as long as a page slides, on the curve of arriving) and swallows touches meanwhile. */
@Composable
fun SlideUpOverlay(content: @Composable () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, androidx.compose.animation.core.tween(Motion.Page, easing = Motion.Arrive)) }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = (1f - progress.value) * size.height }
            .pointerInput(Unit) { detectTapGestures { } },
    ) { content() }
}

/** A page laid over the screen that slides in from the right, like every other page with a back arrow (same length, same curve), and swallows touches meanwhile. */
@Composable
fun SlideInOverlay(content: @Composable () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, androidx.compose.animation.core.tween(Motion.Page, easing = Motion.Arrive)) }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().graphicsLayer { translationX = (1f - progress.value) * size.width }.pointerInput(Unit) { detectTapGestures { } },
    ) { content() }
}

/**
 * The same page laid over the screen, but for something that is opened and closed ([item] set, then null): it slides in with the length and the
 * curve of every page, and slides out again the same way when [item] becomes null (it used to vanish at once), showing the last item meanwhile.
 */
@Composable
fun <T : Any> SlideInOverlayFor(item: T?, content: @Composable (T) -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var shown by remember { androidx.compose.runtime.mutableStateOf(item) }
    if (item != null) shown = item
    LaunchedEffect(item != null) {
        if (item != null) progress.animateTo(1f, androidx.compose.animation.core.tween(Motion.Page, easing = Motion.Arrive))
        else { progress.animateTo(0f, androidx.compose.animation.core.tween(Motion.Page, easing = Motion.Leave)); shown = null }
    }
    val last = shown
    if (last != null) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().graphicsLayer { translationX = (1f - progress.value) * size.width }.pointerInput(Unit) { detectTapGestures { } },
        ) { content(last) }
    }
}

/** The real names of people by their user name (the pickers keep the user name, the pages show the name). Filled as lists of people are read. */
object UserNames {
    private val names = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var loaded = false
    fun remember(user: Jv) { val id = user["userName"].text; val name = user["realName"].text; if (id.isNotEmpty() && name.isNotEmpty()) names[id] = name }
    fun show(userName: String): String = names[userName] ?: userName
    /** Reads the list of people once, so a user name that was filled in by a default or by the process can be shown as a name. */
    suspend fun ensure(session: com.personal.tpm.data.Session) {
        if (loaded) return
        loaded = true
        try { session.request("security/getSelectableUsersByFactory").array.forEach { remember(it) } }
        catch (e: kotlinx.coroutines.CancellationException) { loaded = false; throw e }
        catch (e: Exception) { loaded = false }
    }
}

/** Remembers where on a row the finger went down (relative to the row), without taking the touch from anyone: for a menu that opens under the finger. */
fun Modifier.trackTouch(onTouch: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            event.changes.firstOrNull { it.pressed }?.let { onTouch(it.position) }
        }
    }
}
