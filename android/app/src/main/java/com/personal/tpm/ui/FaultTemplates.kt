package com.personal.tpm.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.FaultTitlePattern
import org.json.JSONArray
import java.util.UUID

enum class FaultTemplateField(val key: String, val title: String, val source: RefSource? = null, val limit: Int = 64) {
    Name("faultName", "报修名称"),
    Asset("asset", "报修资产", RefSource.Assets),
    Applicant("applyUser", "故障申请人", RefSource.Users),
    ApplicantDepartment("applyDept", "申请部门", RefSource.Departments),
    ReceivingDepartment("dept", "受理部门", RefSource.Departments),
    RepairReceiver("user", "维修受理人", RefSource.Users),
    Phone("userPhone", "报修人联系电话", limit = 32),
    RepairGroup("userGroup", "维修班组", RefSource.Groups),
    AccountingDepartment("division", "ERP记账部门", RefSource.Divisions),
    Description("faultDesc", "故障描述", limit = 255),
}

data class FaultTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val values: Map<FaultTemplateField, Jv> = emptyMap(),
    val checked: Set<FaultTemplateField> = values.keys,
    val preset: Boolean = false,
    val titlePattern: FaultTitlePattern? = null,
    val singleTitle: String = "",
) {
    fun value(field: FaultTemplateField) = values[field] ?: Jv.NULL

    fun encode() = Jv.obj(
        "id" to id, "name" to name, "preset" to preset,
        "fields" to Jv.obj(*values.map { (field, value) -> field.key to value.raw }.toTypedArray()),
        "checked" to JSONArray(checked.map { it.key }),
        "titleFormat" to titlePattern?.format,
        "singleTitle" to singleTitle,
    )

    companion object {
        val blank = FaultTemplate(id = "blank", name = "单次报修")
        val retiredPresets = setOf("startup", "noise", "leak")

        fun decode(value: Jv): FaultTemplate? {
            val id = value["id"].text
            if (id.isBlank()) return null
            val fields = value["fields"]
            val values = FaultTemplateField.entries.mapNotNull { field ->
                val stored = if (fields.isObject) fields[field.key] else when (field) {
                    FaultTemplateField.Name -> value["faultName"]
                    FaultTemplateField.Description -> value["description"]
                    FaultTemplateField.Asset -> value["asset"]
                    else -> Jv.NULL
                }
                if (stored.isNull) null else field to stored
            }.toMap()
            val checked = if (fields.isObject) {
                val keys = value["checked"].array.map { it.text }.toSet()
                FaultTemplateField.entries.filter { it.key in keys }.toSet()
            } else values.keys
            val pattern = FaultTitlePattern.read(value["titleFormat"], value["titlePattern"])
            val singleTitle = if ("singleTitle" in value.keys) value["singleTitle"].text
                else values[FaultTemplateField.Name]?.text?.takeIf { FaultTemplateField.Name in checked }
                    ?: pattern?.takeIf { it.variables.isEmpty() }?.format.orEmpty()
            return FaultTemplate(id, value["name"].text, values, checked, value["preset"].bool, pattern, singleTitle)
        }
    }
}

private fun faultTemplatePreferences(context: Context) =
    context.getSharedPreferences("fault-report-templates", Context.MODE_PRIVATE)

private fun templateStorageKey(scope: String) = "v3|" + scope

fun readFaultTemplates(context: Context, scope: String): List<FaultTemplate> {
    val prefs = faultTemplatePreferences(context)
    fun read(key: String): List<FaultTemplate>? {
        val saved = prefs.getString(key, null)?.let { Jv.parse(it) }
        return if (saved?.raw is JSONArray) saved.array.mapNotNull { FaultTemplate.decode(it) } else null
    }
    return read(templateStorageKey(scope)) ?: migrateFaultPresets(read("v2|$scope") ?: read(scope).orEmpty())
}

@Composable
fun FaultTemplateTab(nested: Modifier, open: (FaultTemplate) -> Unit, openBatch: (FaultTemplate) -> Unit, edit: (String) -> Unit) {
    PageWidth(maxWidth = ContentMaxWidth) {
        Column(
            nested.fillMaxSize().imePadding().pageVerticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FaultTemplateContent(open, edit, openBatch)
        }
    }
}

@Composable
fun AssetFaultTemplateScreen(asset: Jv, onBack: () -> Unit, edit: (String) -> Unit, open: (FaultTemplate) -> Unit) {
    FormPage(title = "使用此资产报修", busy = false, onClose = onBack, bottomBar = {}) {
        GroupCard {
            Text(
                listOf(asset["assetCode"].text, asset.label).filter(String::isNotBlank).joinToString(" · "),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        FaultTemplateContent(open, edit)
    }
}

@Composable
private fun FaultTemplateContent(open: (FaultTemplate) -> Unit, edit: (String) -> Unit, openBatch: ((FaultTemplate) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = LocalSession.current.draftScope
    val prefs = remember(context) { faultTemplatePreferences(context) }
    var templates by remember(context, scope) { mutableStateOf(readFaultTemplates(context, scope)) }
    var deleteTarget by remember { mutableStateOf<FaultTemplate?>(null) }
    DisposableEffect(prefs, scope) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == templateStorageKey(scope)) templates = readFaultTemplates(context, scope)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        templates = readFaultTemplates(context, scope)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportEntryCard("单次报修", "填写一张报修单", Icons.Filled.Add, Modifier.weight(1f)) { open(FaultTemplate.blank) }
        if (openBatch != null) ReportEntryCard("批量报修", "为多台设备报修", Icons.Filled.LibraryAdd, Modifier.weight(1f)) {
            openBatch(FaultTemplate.blank)
        }
    }
    listOf(true to "预设模板", false to "自定义模板").forEach { (preset, title) ->
        val group = templates.filter { it.preset == preset }
        if (group.isNotEmpty() || !preset) FormTitle(title)
        group.forEach { template ->
            TemplateRow(template, { open(template) }, openBatch?.let { { it(template) } }, { edit(template.id) }) { deleteTarget = template }
        }
    }
    TextButton(onClick = { edit("") }) { Text("新建模板") }
    deleteTarget?.let { target ->
        AnimatedMessageDialog(visible = true, onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name}」模板？") },
            text = { Text("这会移除当前账号和厂区保存的模板。") },
            confirmButton = {
                TextButton(onClick = { writeFaultTemplates(context, scope, templates.filterNot { it.id == target.id }); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
}

private fun writeFaultTemplates(context: Context, scope: String, templates: List<FaultTemplate>) {
    val encoded = JSONArray(templates.map { it.encode() })
    faultTemplatePreferences(context).edit().putString(templateStorageKey(scope), encoded.toString()).apply()
}

@Composable
internal fun FaultTemplateEditorScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = LocalSession.current.draftScope
    var encoded by rememberSaveable(scope, id) {
        val initial = if (id.isBlank()) FaultTemplate() else readFaultTemplates(context, scope).firstOrNull { it.id == id }
        mutableStateOf(initial?.encode()?.toString())
    }
    val template = encoded?.let { FaultTemplate.decode(Jv.parse(it)) }
    FormPage(title = if (id.isBlank()) "新建报修模板" else "编辑报修模板", busy = false, onClose = onBack, bottomBar = {}) {
        if (template == null) EmptyState("找不到这个报修模板")
        else FaultTemplateEditor(template, change = { encoded = it.encode().toString() }, close = onBack) { saved ->
            val latest = readFaultTemplates(context, scope)
            val next = if (latest.any { it.id == saved.id }) latest.map { if (it.id == saved.id) saved else it } else latest + saved
            writeFaultTemplates(context, scope, next)
            onBack()
        }
    }
}

@Composable
private fun ReportEntryCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, open: () -> Unit) {
    Card(onClick = open, modifier = modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Badge(icon, MaterialTheme.colorScheme.primary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TemplateRow(template: FaultTemplate, open: () -> Unit, openBatch: (() -> Unit)?, edit: () -> Unit, delete: () -> Unit) {
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = open).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart) {
                Column {
                    Text(template.name)
                    template.titlePattern?.let {
                        Text(it.format, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val accent = MaterialTheme.colorScheme.primary
            val error = MaterialTheme.colorScheme.error
            if (openBatch != null) IconButton(onClick = openBatch, colors = IconButtonDefaults.iconButtonColors(contentColor = accent)) {
                Icon(Icons.Filled.LibraryAdd, "批量使用 ${template.name}", Modifier.size(20.dp))
            }
            IconButton(onClick = edit, colors = IconButtonDefaults.iconButtonColors(contentColor = accent)) {
                Icon(Icons.Filled.Edit, "编辑模板 ${template.name}", Modifier.size(20.dp))
            }
            IconButton(onClick = delete, colors = IconButtonDefaults.iconButtonColors(contentColor = error)) {
                Icon(Icons.Filled.Delete, "删除模板 ${template.name}", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FaultTemplateEditor(
    template: FaultTemplate,
    change: (FaultTemplate) -> Unit,
    close: () -> Unit,
    save: (FaultTemplate) -> Unit,
) {
    var picker by remember { mutableStateOf<PickRequest?>(null) }
    TextEntry("模板名称", template.name, { change(template.copy(name = it)) }, required = true, limit = 64)
    Text("勾选需要预填的字段，其余字段在报修时填写。", style = MaterialTheme.typography.bodySmall)
    FaultTemplateField.entries.filter { it != FaultTemplateField.Name }.forEach { field ->
        val checked = field in template.checked
        val value = template.value(field)
        val toggle = {
            change(template.copy(checked = if (checked) template.checked - field else template.checked + field))
        }
        GroupCard {
            Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { toggle() })
                Text(field.title)
            }
            if (checked) {
                val source = field.source
                if (source == null) {
                    TextEntry(field.title, value.text, { change(template.copy(values = template.values + (field to Jv(it)))) },
                        limit = field.limit, minLines = if (field == FaultTemplateField.Description) 3 else 1,
                        keyboardType = if (field == FaultTemplateField.Phone) androidx.compose.ui.text.input.KeyboardType.Phone else androidx.compose.ui.text.input.KeyboardType.Text)
                } else {
                    PickRow(field.title, value.faultTemplateLabel(), true) {
                        picker = PickRequest(source, { chosen -> change(template.copy(values = template.values + (field to chosen))) })
                    }
                }
            }
        }
    }
    val checked = template.checked - FaultTemplateField.Name
    val canSave = template.name.isNotBlank() &&
        checked.all { field -> if (field.source == null) template.value(field).text.isNotBlank() else template.value(field).isObject }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = close) { Text("取消") }
        GlassActionButton(onClick = { save(template.copy(name = template.name.trim(), checked = checked,
            values = template.values.filterKeys { it in checked })) },
            enabled = canSave) {
            Text("保存模板")
        }
    }
    PickHost(picker) { picker = null }
}
