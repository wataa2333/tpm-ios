package com.personal.tpm.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.PickerPrefs
import com.personal.tpm.data.Session
import com.personal.tpm.data.defaultFields
import com.personal.tpm.data.resolveReference
import com.personal.tpm.data.serverDate
import com.personal.tpm.data.usernames
import com.personal.tpm.data.withApproverDefaults
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * What a form (a repair report or an ERP outbound order) holds while it is filled in: the record as JSON (changed by replacing it, so the
 * screen follows), the approver steps of the process and who is picked for each. A port of OriginalFormModel.
 */
@Stable
class FormState(val kind: OrderKind) {
    var data by mutableStateOf(JSONObject())
    var nodes by mutableStateOf<List<Jv>>(emptyList())
    var selections by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var loading by mutableStateOf(true)
    var ready by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val jv: Jv get() = Jv(data)
    operator fun get(key: String): Jv = Jv(data.opt(key))

    fun set(key: String, value: Any?) { data = JSONObject(data.toString()).put(key, value ?: JSONObject.NULL) }
    fun setAll(vararg pairs: Pair<String, Any?>) {
        val copy = JSONObject(data.toString())
        pairs.forEach { (k, v) -> copy.put(k, v ?: JSONObject.NULL) }
        data = copy
    }
    fun update(key: String, change: (List<String>) -> List<String>) { selections = selections + (key to change(selections[key].orEmpty())) }

    /** Choosing a receiving department also fills in its default assignee (the person can still pick another). */
    fun selectReceiverDepartment(department: Jv) = setAll("dept" to department.raw, "user" to department["acceptUser"].raw)

    /** Reads the order being edited (or the person's own details for a new one) and the approver steps of the process. */
    suspend fun load(session: Session, existingId: String?, referenceSn: String? = null) {
        loading = true; ready = false; error = null
        try {
            val record: JSONObject
            if (existingId != null) {
                record = JSONObject(session.request(kind.detailPath, mapOf(kind.idKey to existingId)).raw.toString())
                if (kind == OrderKind.Erp) record.put("erpMaterialOutbounds", session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to existingId)).raw ?: JSONArray())
            } else {
                val user = session.request("user/getUserDeptByUserId", mapOf("userId" to session.userId))
                val department = user["depts"].array.firstOrNull()?.raw ?: JSONObject.NULL
                record = JSONObject().put("assignees", JSONObject())
                if (kind == OrderKind.Fault) {
                    record.put("applyUser", user.raw).put("applyDept", department).put("userPhone", user["mobile"].raw ?: JSONObject.NULL)
                        .put("faultTime", serverDate(Date())).put("albums", JSONArray())
                } else {
                    record.put("user", user.raw).put("recipientUser", user.raw).put("dept", department)
                        .put("applyDate", serverDate(Date(), dateOnly = true)).put("outboundTime", serverDate(Date())).put("erpMaterialOutbounds", JSONArray())
                    if (!referenceSn.isNullOrEmpty()) record.put("referenceSn", referenceSn)
                }
            }
            val steps = session.request("workflow/getAssigneeElementsByProcessCategory", mapOf("processCategory" to kind.prefix)).array
            // Approvers already chosen on an existing order (the server may keep them as JSON text).
            var existing = Jv(record.opt("assignees"))
            if (existing.raw is String && existing.text.startsWith("{")) existing = Jv.parse(existing.text)
            selections = steps.associate { node ->
                val key = node["variableName"].text
                key to usernames(if (existing[key].isNull) node["assignees"] else existing[key])
            }
            nodes = steps
            data = record
            ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败" }
        finally { loading = false }
    }

    /** Changing only the actor must not replace the user's entered values. */
    suspend fun refreshActor(session: Session, existingId: String?, referenceSn: String? = null) {
        val kept = JSONObject(data.toString())
        val previous = nodes.associate { com.personal.tpm.data.quickApproverKey(it) to selections[it["variableName"].text].orEmpty() }
        load(session, existingId, referenceSn)
        data = kept
        if (ready) selections = com.personal.tpm.data.inheritedQuickApprovers(nodes, selections, previous)
    }

    /** A new form: the fields the person made a default for are filled in, then the default approvers. */
    suspend fun applyDefaults(session: Session, prefs: PickerPrefs) {
        val scope = session.draftScope
        selections = withApproverDefaults(nodes, selections, prefs, scope, if (kind == OrderKind.Fault) "assignee.fault" else "assignee.erp")
        for (item in defaultFields(kind)) {
            val choice = prefs.defaultChoice(scope, item.field) ?: continue
            val result = try { resolveReference(session, item.source, choice.value) } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
            if (kind == OrderKind.Fault && item.dataKey == "dept") selectReceiverDepartment(result)
            else {
                set(item.dataKey, result.raw)
                if (kind == OrderKind.Fault && item.dataKey == "applyUser") result["depts"].array.firstOrNull()?.let { set("applyDept", it.raw) }
            }
        }
    }

    /** Puts a draft over the freshly loaded form. */
    fun applyDraft(draft: com.personal.tpm.data.FormDraft) {
        data = JSONObject(draft.data.toString())
        selections = selections + draft.selections
    }

    /** Adds a stock material as a line of an ERP order. */
    fun addMaterial(material: Jv) {
        val lines = this["erpMaterialOutbounds"].array
        if (lines.any { it["material"]["id"] == material["id"] }) { error = "已选择此出库物料"; return }
        val line = Jv.obj(
            "material" to material.raw, "id" to Jv.obj("imaterialId" to material["id"].raw, "outboundId" to (this["outboundId"].raw)), "entryQuantity" to 1,
        )
        set("erpMaterialOutbounds", JSONArray().also { a -> lines.forEach { a.put(it.raw) }; a.put(line) })
    }

    fun setQuantity(index: Int, quantity: Double) {
        val lines = this["erpMaterialOutbounds"].array
        set("erpMaterialOutbounds", JSONArray().also { a -> lines.forEachIndexed { i, l -> a.put(if (i == index) JSONObject(l.raw.toString()).put("entryQuantity", quantity) else l.raw) } })
    }

    fun removeLine(index: Int) {
        val lines = this["erpMaterialOutbounds"].array
        set("erpMaterialOutbounds", JSONArray().also { a -> lines.forEachIndexed { i, l -> if (i != index) a.put(l.raw) } })
    }
}
