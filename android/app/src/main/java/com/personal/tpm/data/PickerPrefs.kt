package com.personal.tpm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Pinned, recent and default choices for the selection sheets (people, groups, ...), kept on this phone per account and factory. Nothing here
// is sent to the server and nothing secret is stored: an entry is the row the server listed.

/** What a selection sheet lists. */
enum class RefSource(val title: String) {
    Users("选择人员"), WorkflowUsers("选择人员"), Groups("选择维修班组"), Assets("选择资产"), Departments("选择部门"), Divisions("ERP记账部门"),
    Projects("选择项目"), Materials("选择出库物料"), FaultTypes("选择故障类型"), OutboundMaterials("选择已出库物料");

    /** The path of a list the server pages and searches; the others are read whole. */
    val pagePath: String? get() = when (this) {
        Assets -> "asset/pageAsset"; Materials -> "erpOutbound/pageErpMaterial"; OutboundMaterials -> "erpOutbound/pageErpMaterialOutbound"; else -> null
    }
    /** Materials are chosen by quantity, not by preference: no pins, recents or defaults there. */
    val hasMenu: Boolean get() = this != Materials && this != OutboundMaterials
}

/** The ways a server-searched list can be searched by. */
enum class PickerSearch(val label: (RefSource) -> String) {
    Name({ if (it == RefSource.Assets) "名称" else "物料名称" }), Code({ if (it == RefSource.Assets) "资产编码" else "料号" }),
    Recipient({ "领用人" }), OutNumber({ "出库单号" });

    fun field(source: RefSource): String = when (this) {
        Name -> when (source) { RefSource.Assets -> "assetName"; RefSource.Materials -> "materialDesc"; else -> "material.materialDesc" }
        Code -> when (source) { RefSource.Assets -> "assetCode"; RefSource.Materials -> "materialCode"; else -> "material.materialCode" }
        Recipient -> "outbound.recipientUser.realName"
        OutNumber -> "outbound.outNumber"
    }
}

fun searchModes(source: RefSource): List<PickerSearch> = when (source) {
    RefSource.Assets, RefSource.Materials -> listOf(PickerSearch.Name, PickerSearch.Code)
    RefSource.OutboundMaterials -> listOf(PickerSearch.Name, PickerSearch.Code, PickerSearch.Recipient, PickerSearch.OutNumber)
    else -> emptyList()
}

data class PickerEntry(val key: String, val label: String, val value: Jv)

data class PickerLists(val pins: List<PickerEntry> = emptyList(), val recents: List<PickerEntry> = emptyList()) {
    companion object { const val PIN_LIMIT = 10; const val RECENT_SHOWN = 3; const val RECENT_KEPT = 8 }
}

/** The pure rules, so they can be tested without a screen. */
object PickerRules {
    /** Pins an entry at the top. Returns null when the ten places are taken. */
    fun pin(entry: PickerEntry, lists: PickerLists): PickerLists? {
        if (lists.pins.any { it.key == entry.key }) return lists
        if (lists.pins.size >= PickerLists.PIN_LIMIT) return null
        return lists.copy(pins = listOf(entry) + lists.pins)
    }
    fun unpin(key: String, lists: PickerLists) = lists.copy(pins = lists.pins.filter { it.key != key })
    /** A chosen entry moves to the front of the recent ones. */
    fun record(entry: PickerEntry, lists: PickerLists) = lists.copy(recents = (listOf(entry) + lists.recents.filter { it.key != entry.key }).take(PickerLists.RECENT_KEPT))
    /** The recent choices to show: not already pinned, at most three. */
    fun shownRecents(lists: PickerLists): List<PickerEntry> {
        val pinned = lists.pins.map { it.key }.toSet()
        return lists.recents.filter { it.key !in pinned }.take(PickerLists.RECENT_SHOWN)
    }
}

/** What identifies a row of a selection sheet. */
fun referenceKey(source: RefSource, value: Jv): String {
    if (source == RefSource.OutboundMaterials) return outboundLineIdentity(value)
    val keys = when (source) {
        RefSource.Users, RefSource.WorkflowUsers -> listOf("userName", "userId")
        RefSource.Groups -> listOf("groupId", "id")
        RefSource.Departments -> listOf("value", "id")
        RefSource.Divisions -> listOf("divisionId", "id")
        RefSource.Projects -> listOf("projectCode", "id")
        RefSource.FaultTypes -> listOf("key", "id")
        else -> listOf("id")
    }
    for (key in keys) value[key].text.takeIf { it.isNotEmpty() }?.let { return it }
    return value.label
}

class PickerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tpm_picker", Context.MODE_PRIVATE)

    private fun listKey(scope: String, source: RefSource) = "list|$scope|${source.name}"
    private fun defaultKey(scope: String, field: String) = "default|$scope|$field"

    private fun entryJson(entry: PickerEntry) = JSONObject().put("k", entry.key).put("l", entry.label).put("v", entry.value.raw ?: JSONObject.NULL)
    private fun entryFrom(json: JSONObject) = PickerEntry(json.optString("k"), json.optString("l"), Jv(json.opt("v")))

    fun lists(scope: String, source: RefSource): PickerLists = try {
        val json = JSONObject(prefs.getString(listKey(scope, source), null) ?: "{}")
        fun read(name: String) = json.optJSONArray(name)?.let { array -> List(array.length()) { entryFrom(array.getJSONObject(it)) } } ?: emptyList()
        PickerLists(read("pins"), read("recents"))
    } catch (e: Exception) { PickerLists() }

    fun save(scope: String, source: RefSource, lists: PickerLists) {
        val json = JSONObject()
            .put("pins", JSONArray(lists.pins.map(::entryJson)))
            .put("recents", JSONArray(lists.recents.map(::entryJson)))
        prefs.edit().putString(listKey(scope, source), json.toString()).apply()
    }

    fun defaultChoice(scope: String, field: String): PickerEntry? = try {
        prefs.getString(defaultKey(scope, field), null)?.let { entryFrom(JSONObject(it)) }
    } catch (e: Exception) { null }

    fun setDefault(scope: String, field: String, entry: PickerEntry?) {
        prefs.edit().apply { if (entry == null) remove(defaultKey(scope, field)) else putString(defaultKey(scope, field), entryJson(entry).toString()) }.apply()
    }
}
