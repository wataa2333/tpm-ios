package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Narrow seam for offline workflow tests. The live adapter keeps the original endpoints and payloads. */
internal interface BatchApi {
    val accountName: String
    suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: Any? = null): Jv
    suspend fun page(path: String, offset: Int = 0, filters: List<JSONObject> = emptyList(), limit: Int = 20): Jv =
        request(path, body = Jv.obj("offset" to offset, "limit" to limit, "sort" to "createTime", "order" to "desc", "filters" to JSONArray(filters)))
    fun approverDefaults(nodes: List<Jv>, picked: Map<String, List<String>>, kind: OrderKind): Map<String, List<String>> = picked
}

internal class LiveBatchApi(private val session: Session, private val prefs: PickerPrefs) : BatchApi {
    private val originalScope = session.scope.value
    override val accountName get() = session.accountName
    override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv {
        currentCoroutineContext().ensureActive()
        if (!session.signedIn || session.scope.value != originalScope) throw CancellationException("批量处理的账号或厂区已改变")
        return session.request(path, query, body).also {
            if (session.scope.value != originalScope) throw CancellationException("批量处理的账号或厂区已改变")
        }
    }
    override fun approverDefaults(nodes: List<Jv>, picked: Map<String, List<String>>, kind: OrderKind) =
        withApproverDefaults(nodes, picked, prefs, session.draftScope, "assignee.task." + kind.prefix)
}
