package com.personal.tpm.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.RefSource
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
) {
    fun value(field: FaultTemplateField) = values[field] ?: Jv.NULL

    fun encode() = Jv.obj(
        "id" to id, "name" to name, "preset" to preset,
        "fields" to Jv.obj(*values.map { (field, value) -> field.key to value.raw }.toTypedArray()),
        "checked" to JSONArray(checked.map { it.key }),
    )

    companion object {
        val blank = FaultTemplate(id = "blank", name = "空白报修")
        val presets = listOf(
            FaultTemplate(id = "parts-repair", name = "配件维修", preset = true),
            FaultTemplate(id = "facilities-repair", name = "厂务维修", preset = true),
        )
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
            return FaultTemplate(id, value["name"].text, values, checked, value["preset"].bool)
        }
    }
}

private fun faultTemplatePreferences(context: Context) =
    context.getSharedPreferences("fault-report-templates", Context.MODE_PRIVATE)

private fun templateStorageKey(scope: String) = "v2|" + scope

fun readFaultTemplates(context: Context, scope: String): List<FaultTemplate> {
    val prefs = faultTemplatePreferences(context)
    fun read(key: String): List<FaultTemplate>? {
        val saved = prefs.getString(key, null)?.let { Jv.parse(it) }
        return if (saved?.raw is JSONArray) saved.array.mapNotNull { FaultTemplate.decode(it) } else null
    }
    return read(templateStorageKey(scope)) ?: (FaultTemplate.presets +
        read(scope).orEmpty().filterNot { it.id in FaultTemplate.retiredPresets })
}

@Composable
fun FaultTemplateTab(nested: Modifier, open: (FaultTemplate) -> Unit) {
    PageWidth(maxWidth = ContentMaxWidth) {
        Column(
            nested.fillMaxSize().imePadding().pageVerticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FaultTemplateContent(open)
        }
    }
}

@Composable
fun AssetFaultTemplateScreen(asset: Jv, onBack: () -> Unit, open: (FaultTemplate) -> Unit) {
    FormPage(title = "使用此资产报修", busy = false, onClose = onBack, bottomBar = {}) {
        GroupCard {
            Text(
                listOf(asset["assetCode"].text, asset.label).filter(String::isNotBlank).joinToString(" · "),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        FaultTemplateContent(open)
    }
}

@Composable
private fun FaultTemplateContent(open: (FaultTemplate) -> Unit) {
    val context = LocalContext.current
    val scope = LocalSession.current.draftScope
    val prefs = remember(context) { faultTemplatePreferences(context) }
    var templates by remember(context, scope) { mutableStateOf(readFaultTemplates(context, scope)) }
    var editor by rememberSaveable(scope) { mutableStateOf<String?>(null) }

    fun store(next: List<FaultTemplate>) {
        val encoded = JSONArray()
        next.forEach { encoded.put(it.encode()) }
        prefs.edit().putString(templateStorageKey(scope), encoded.toString()).apply()
        templates = next
    }

    val editing = editor?.let { FaultTemplate.decode(Jv.parse(it)) }
    if (editing != null) {
        val backMotion = predictivePageBack(enabled = true) { editor = null }
        Column(Modifier.fillMaxWidth().then(backMotion), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FaultTemplateEditor(editing, change = { editor = it.encode().toString() }, close = { editor = null }) { saved ->
                val existing = templates.any { it.id == saved.id }
                store(if (existing) templates.map { if (it.id == saved.id) saved else it } else templates + saved)
                editor = null
            }
        }
    } else {
        GroupCard { PickRow("空白报修", "", false) { open(FaultTemplate.blank) } }
        val presets = templates.filter { it.preset }
        if (presets.isNotEmpty()) {
            FormTitle("预设模板")
            presets.forEach { template ->
                TemplateRow(template, { open(template) }, { editor = template.encode().toString() }) {
                    store(templates.filterNot { it.id == template.id })
                }
            }
        }
        FormTitle("自定义模板")
        templates.filterNot { it.preset }.forEach { template ->
            TemplateRow(template, { open(template) }, { editor = template.encode().toString() }) {
                store(templates.filterNot { it.id == template.id })
            }
        }
        TextButton(onClick = { editor = FaultTemplate().encode().toString() }) { Text("新建模板") }
    }
}

@Composable
private fun TemplateRow(template: FaultTemplate, open: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = open).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(template.name)
            }
            val accent = MaterialTheme.colorScheme.primary
            val error = MaterialTheme.colorScheme.error
            IconButton(onClick = edit) {
                Icon(Icons.Filled.Edit, "编辑模板 ${template.name}", tint = accent)
            }
            IconButton(onClick = delete) {
                Icon(Icons.Filled.Delete, "删除模板 ${template.name}", tint = error)
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
    FaultTemplateField.entries.forEach { field ->
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
                    PickRow(field.title, value.label, true) {
                        picker = PickRequest(source, { chosen -> change(template.copy(values = template.values + (field to chosen))) })
                    }
                }
            }
        }
    }
    val canSave = template.name.isNotBlank() &&
        template.checked.all { field -> if (field.source == null) template.value(field).text.isNotBlank() else template.value(field).isObject }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = close) { Text("取消") }
        GlassActionButton(onClick = { save(template.copy(name = template.name.trim(), values = template.values.filterKeys { it in template.checked })) },
            enabled = canSave) {
            Text("保存模板")
        }
    }
    PickHost(picker) { picker = null }
}
