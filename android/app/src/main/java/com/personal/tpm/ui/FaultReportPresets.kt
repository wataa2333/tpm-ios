package com.personal.tpm.ui

import com.personal.tpm.data.Jv
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.Session
import com.personal.tpm.data.resolveReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val PresetReferenceName = "presetReferenceName"

private fun presetReference(name: String) = Jv(Jv.obj(PresetReferenceName to name))

internal fun Jv.faultTemplateLabel() = this[PresetReferenceName].text.ifBlank { label }

internal val defaultFaultPresets = listOf(
    FaultTemplate(id = "parts-repair", name = "配件维修", preset = true, values = mapOf(
        FaultTemplateField.ReceivingDepartment to presetReference("茶山技改维修部-配件维修科"),
        FaultTemplateField.RepairReceiver to presetReference("吴丽"),
        FaultTemplateField.RepairGroup to presetReference("茶山技改维修部-配件维修组"),
    )),
    FaultTemplate(id = "facilities-repair", name = "厂务维修", preset = true, values = mapOf(
        FaultTemplateField.ReceivingDepartment to presetReference("茶山技改维修部-厂务科"),
        FaultTemplateField.RepairReceiver to presetReference("吴丽"),
        FaultTemplateField.RepairGroup to presetReference("茶山技改维修部-厂务组"),
    )),
    FaultTemplate(id = "cnc-repair", name = "CNC维修部", preset = true, values = mapOf(
        FaultTemplateField.ApplicantDepartment to presetReference("茶山技改维修部-设备维修科"),
        FaultTemplateField.ReceivingDepartment to presetReference("广东CNC维修部"),
        FaultTemplateField.RepairGroup to presetReference("广东CNC维修部组"),
    )),
)

/** Keep saved edits and supply the new defaults for older built-in templates. */
internal fun migrateFaultPresets(saved: List<FaultTemplate>): List<FaultTemplate> =
    defaultFaultPresets.map { preset ->
        val old = saved.firstOrNull { it.id == preset.id } ?: return@map preset
        val defaults = preset.values.filterKeys { it !in old.checked || !old.value(it).isObject }
        old.copy(preset = true, values = old.values + defaults, checked = defaults.keys + old.checked)
    } + saved.filterNot { old -> defaultFaultPresets.any { it.id == old.id } || old.id in FaultTemplate.retiredPresets }

internal data class FaultPresetResolution(val values: Map<FaultTemplateField, Jv>, val unavailable: List<String>)

/** Resolve labels with the active operator's references before they can become form data. */
internal suspend fun Session.resolveFaultPreset(template: FaultTemplate): FaultPresetResolution = coroutineScope {
    val values = template.values.filterKeys { it in template.checked }.toMutableMap()
    val names = values.mapNotNull { (field, value) ->
        value[PresetReferenceName].text.takeIf(String::isNotBlank)?.let { field to it }
    }.toMap()
    val missing = mutableListOf<String>()
    suspend fun read(block: suspend () -> Jv): Jv = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Jv.NULL }
    val deptFields = listOf(FaultTemplateField.ApplicantDepartment, FaultTemplateField.ReceivingDepartment)
    val departments = async {
        if (deptFields.none { it in names }) emptyList()
        else departmentReferences(read { request("dept/getDeptSimpleTree") }.array)
    }
    val groups = async {
        if (FaultTemplateField.RepairGroup !in names) emptyList()
        else read { request("user/getEnabledUserGroupsByGroupType", mapOf("groupType" to "REPAIR")) }.array
    }
    suspend fun resolve(field: FaultTemplateField, lookup: suspend (String) -> Jv) {
        val name = names[field] ?: return
        val result = read { lookup(name) }
        values[field] = result
        if (result.isNull) missing += "${field.title}「$name」"
    }
    for (field in deptFields) resolve(field) { name ->
        val row = departments.await().filter { it.label == name }.distinctBy { it["value"].text }.singleOrNull()
        if (row == null) Jv.NULL else resolveReference(this@resolveFaultPreset, RefSource.Departments, row)
            .takeIf { it.isObject && it.keys.isNotEmpty() } ?: Jv.NULL
    }
    resolve(FaultTemplateField.RepairGroup) { name ->
        groups.await().filter { it.label == name }.singleOrNull() ?: Jv.NULL
    }
    resolve(FaultTemplateField.RepairReceiver) { name ->
        val department = values[FaultTemplateField.ReceivingDepartment] ?: Jv.NULL
        val assigned = department["acceptUser"]
        if (assigned.label == name && assigned["enabled"].raw != false &&
            (assigned["userId"].text.isNotBlank() || assigned["id"].text.isNotBlank())) assigned
        else {
            val matches = request("security/getSelectableUsersByFactory").array.filter {
                it["enabled"].raw != false && it["realName"].text == name && it["userId"].text.isNotBlank()
            }.distinctBy { it["userId"].text }
            val details = matches.map { row -> read { resolveReference(this@resolveFaultPreset, RefSource.Users, row) } }
                .filter { it.isObject && it.keys.isNotEmpty() }
            val deptId = department["deptId"].text.ifBlank { department["id"].text }
            if (matches.size == 1) details.singleOrNull() ?: Jv.NULL
            else details.takeIf { it.size == matches.size }?.singleOrNull { user ->
                user["depts"].array.any { dept -> deptId.isNotBlank() && deptId == dept["deptId"].text.ifBlank { dept["id"].text } }
            } ?: Jv.NULL
        }
    }
    FaultPresetResolution(FaultTemplateField.entries.mapNotNull { field -> values[field]?.let { field to it } }.toMap(), missing)
}

private fun departmentReferences(nodes: List<Jv>): List<Jv> = nodes.flatMap { row ->
    val id = row["value"].text.ifBlank { row["deptId"].text.ifBlank { row["id"].text } }
    val entry = if (id.isBlank() || id == "root" || row["disabled"].bool) emptyList()
        else listOf(Jv(Jv.obj("value" to id, "label" to row.label)))
    entry + departmentReferences(row["children"].array)
}
