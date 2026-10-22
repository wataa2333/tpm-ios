package com.personal.tpm.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON value read the forgiving way, like the iOS app's JSONValue: a missing key is just an empty value, so
 * `detail["asset"]["assetCode"].text` never throws. Built on org.json, which Android ships.
 */
@Immutable
class Jv(val raw: Any?) {
    val isNull: Boolean get() = raw == null || raw === JSONObject.NULL
    operator fun get(key: String): Jv = Jv((raw as? JSONObject)?.opt(key))
    fun at(path: String): Jv = path.split('.').fold(this) { value, key -> value[key] }
    val array: List<Jv> get() = (raw as? JSONArray)?.let { list -> List(list.length()) { Jv(list.opt(it)) } } ?: emptyList()
    val isObject: Boolean get() = raw is JSONObject
    val keys: List<String> get() = (raw as? JSONObject)?.keys()?.asSequence()?.toList() ?: emptyList()
    val bool: Boolean get() = raw as? Boolean ?: false
    val number: Double get() = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    val text: String get() = when (raw) {
        null -> ""
        JSONObject.NULL -> ""
        is String -> raw
        is Boolean -> raw.toString()
        is Int, is Long -> raw.toString()
        is Number -> raw.toDouble().let { if (it == Math.rint(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        else -> ""
    }
    /** Dates arrive from the server either as text or as [year, month, day, hour, minute, ...]. */
    val dateText: String get() {
        val parts = array.map { it.text }
        return if (parts.size >= 5) "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')} ${parts[3].padStart(2, '0')}:${parts[4].padStart(2, '0')}" else text
    }
    /** The name to show for a person, department, group...: the first label-like field that has text. */
    val label: String get() {
        for (key in listOf("realName", "assetName", "materialDesc", "deptName", "groupName", "divisionDesc", "divisionName", "projectName", "typeName", "title", "label", "taskName", "name", "userName", "sn")) {
            val value = this[key].text
            if (value.isNotEmpty()) return value
        }
        return ""
    }
    override fun equals(other: Any?) = other is Jv && toString() == other.toString()
    override fun hashCode() = toString().hashCode()
    override fun toString(): String = if (isNull) "null" else raw.toString()

    companion object {
        val NULL = Jv(null)
        fun parse(text: String): Jv = try { Jv(org.json.JSONTokener(text).nextValue()) } catch (e: Exception) { NULL }
        fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    }
}
