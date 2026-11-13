package com.personal.tpm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// The draft box: a form that was filled in but not submitted is kept on this phone, per account and per factory. "保存" writes a draft and
// sends nothing; only "提交" talks to the server. A draft holds what the form holds (names, references, uploaded photo references), never
// a password or a token; the file is encrypted with a key in the Android Keystore.

class FormDraft(
    val id: String = UUID.randomUUID().toString(),
    val savedAt: Long = System.currentTimeMillis(),
    val data: JSONObject,
    val selections: Map<String, List<String>> = emptyMap(),
    /** The order this draft belongs to: null for a new report, otherwise the id of the order being edited. A draft is only offered to its own order. */
    val orderId: String? = null,
    val orderSn: String? = null,
) {
    /** A draft is named by when it was last edited, to the minute. */
    val title: String get() = titleFormat(savedAt)
    val subtitle: String get() = (if (orderSn.isNullOrEmpty()) "" else "单号 $orderSn · ") + summary
    private val summary: String get() {
        val d = Jv(data)
        for (value in listOf(d["faultName"].text, d["asset"]["assetName"].text, d["faultDesc"].text, d["remark"].text, d["content"].text)) if (value.isNotBlank()) return value.trim()
        return "还没有填写内容"
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("savedAt", savedAt).put("data", data)
        .put("selections", JSONObject().also { o -> selections.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        .put("orderId", orderId ?: JSONObject.NULL).put("orderSn", orderSn ?: JSONObject.NULL)

    companion object {
        fun titleFormat(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
        fun fromJson(json: JSONObject): FormDraft {
            val picked = json.optJSONObject("selections") ?: JSONObject()
            return FormDraft(
                id = json.optString("id"), savedAt = json.optLong("savedAt"), data = json.optJSONObject("data") ?: JSONObject(),
                selections = picked.keys().asSequence().associateWith { key -> picked.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList() },
                orderId = json.opt("orderId")?.takeIf { it != JSONObject.NULL }?.toString(), orderSn = json.opt("orderSn")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
        }
    }
}

/** Which drafts a form or the box may see. A form only ever gets the drafts of its own order. */
sealed class DraftTarget {
    abstract fun matches(draft: FormDraft): Boolean
    object Everything : DraftTarget() { override fun matches(draft: FormDraft) = true }
    object NewReport : DraftTarget() { override fun matches(draft: FormDraft) = draft.orderId == null }
    class Order(val id: String) : DraftTarget() { override fun matches(draft: FormDraft) = draft.orderId == id }
}

class DraftStore(context: Context) {
    private val file = SecureFile(context, "drafts.bin", "tpm_drafts_v1")

    // The file is decrypted with a Keystore key, which can take a few hundred milliseconds: it is read once and kept here (and read early, off the
    // main thread, at start-up), so opening a form or the "我的" page never waits for it.
    @Volatile private var cache: JSONObject? = null
    @Synchronized private fun readAll(): JSONObject {
        cache?.let { return it }
        val loaded = try { JSONObject(file.read() ?: "{}") } catch (e: Exception) { JSONObject() }
        cache = loaded
        return loaded
    }
    @Synchronized private fun writeAll(all: JSONObject) {
        cache = all
        runCatching { file.writeSnapshot(all.toString()) }
    }

    /** Reads the file now (call from a background thread). */
    fun warmUp() { readAll() }

    /** Newest first. */
    @Synchronized fun drafts(scope: String, target: DraftTarget = DraftTarget.Everything): List<FormDraft> {
        val list = readAll().optJSONArray(scope) ?: return emptyList()
        return List(list.length()) { FormDraft.fromJson(list.getJSONObject(it)) }.filter(target::matches).sortedByDescending { it.savedAt }
    }

    /** Saves a new draft, or replaces the one with the same id. */
    @Synchronized fun save(draft: FormDraft, scope: String, durable: Boolean = false) {
        val all = if (durable) JSONObject(readAll().toString()) else readAll()
        val list = all.optJSONArray(scope) ?: JSONArray()
        val kept = JSONArray()
        var replaced = false
        for (i in 0 until list.length()) {
            val other = list.getJSONObject(i)
            if (other.optString("id") == draft.id) { kept.put(draft.toJson()); replaced = true } else kept.put(other)
        }
        if (!replaced) kept.put(draft.toJson())
        all.put(scope, kept)
        if (durable) {
            file.writeSnapshot(all.toString())
            cache = all
        } else writeAll(all)
    }

    @Synchronized fun remove(id: String, scope: String) {
        val all = readAll()
        val list = all.optJSONArray(scope) ?: return
        val kept = JSONArray()
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") != id) kept.put(list.getJSONObject(i))
        if (kept.length() == 0) all.remove(scope) else all.put(scope, kept)
        writeAll(all)
    }
}
