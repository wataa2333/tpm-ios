package com.personal.tpm.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.personal.tpm.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An exact allowlist: a query token can never be used for workflow writes. */
internal fun precisionReadAllowed(path: String, query: Map<String, String>, body: Any?): Boolean = when (path) {
    "precise/getPreciseAnalysis" -> query.isEmpty() && body is JSONObject && body.keys().asSequence().all {
        it in setOf("inspectStates", "faultStates", "maintainStates", "analysisResults", "type", "layerCode", "categoryIds", "useStates", "assetStates")
    }
    "precise/getPreciseOrdersByPreciseId", "precise/getPreciseRecordsByPreciseId" -> body == null && query.keys == setOf("preciseId") && (query["preciseId"]?.toLongOrNull() ?: 0) > 0
    "security/getUserByUserName" -> body == null && query.keys == setOf("userName") && query["userName"].orEmpty().matches(Regex("[A-Za-z0-9_@.\\-]{1,100}"))
    "workflow/getWorkflowHistoryNodesByProcessInstanceId" -> body == null && query.keys == setOf("processInstanceId") && query["processInstanceId"].orEmpty().matches(Regex("[A-Za-z0-9\\-]{1,100}"))
    "fault/getAssetFaultDetailById", "repair/getRepairsByFaultId" -> body == null && query.keys == setOf("faultId") && (query["faultId"]?.toLongOrNull() ?: 0) > 0
    "asset/pageAsset", "precise/pagePrecise", "maintain/pageMaintain", "fault/pageAssetFault", "erpOutbound/pageErpOutbound", "erpOutbound/pageErpMaterial" -> query.isEmpty() && body is JSONObject && body.optInt("limit") > 0 && body.optInt("offset", -1) >= 0 &&
        body.keys().asSequence().all { it in setOf("offset", "limit", "sort", "order", "filters") }
    else -> false
}

/** Separate encrypted credentials and memory-only token; never replaces the signed-in operator. */
class QueryAccounts(context: Context) {
    private val context = context.applicationContext
    private val credentials = SecureFile(this.context, "precision-query.bin", "tpm_precision_query_v1")
    private var saved = Jv.parse(credentials.read().orEmpty())
    var username by mutableStateOf(if (saved["operator"].bool) "" else saved["username"].text.ifEmpty { BuildConfig.DEFAULT_QUERY_USERNAME }); private set
    var revision by mutableIntStateOf(0); private set
    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()
    private fun revised() { revision++; _changes.value = revision }
    fun hasCredential(name: String) = name.trim() == username && (saved["password"].text.isNotEmpty() || (username == BuildConfig.DEFAULT_QUERY_USERNAME && BuildConfig.DEFAULT_QUERY_PASSWORD.isNotEmpty()))
    private var reader: Session? = null
    private var binding: String? = null
    private val mutex = Mutex()

    fun invalidate() { reader?.signOut(); reader = null; binding = null }
    fun useOperator() {
        val marker = Jv.obj("operator" to true)
        credentials.write(marker.toString())
        check(Jv.parse(credentials.read().orEmpty()) == Jv(marker)) { "无法删除查询凭据，请重试" }
        invalidate(); saved = Jv(marker); username = ""; revised()
    }

    private suspend fun connect(owner: Session, name: String, password: String): Session {
        val scope = owner.scope.value
        val factory = owner.currentFactoryId
        require(owner.signedIn && factory.isNotEmpty()) { "请先登录并选择厂区" }
        val source = Session(context, queryOnly = true)
        source.login(name, password)
        if (owner.scope.value != scope) { source.signOut(); throw CancellationException("scope changed") }
        try {
            source.changeFactory(factory)
            if (owner.scope.value != scope) throw CancellationException("scope changed")
            return source
        } catch (e: Exception) { source.signOut(); throw e }
    }

    suspend fun configure(owner: Session, name: String, password: String) = mutex.withLock {
        val requestedRevision = revision
        val clean = name.trim()
        val secret = password.ifEmpty { if (hasCredential(clean)) saved["password"].text.ifEmpty { BuildConfig.DEFAULT_QUERY_PASSWORD } else "" }
        require(clean.isNotEmpty() && secret.isNotEmpty()) { "请输入查询账号和密码" }
        val source = connect(owner, clean, secret)
        if (revision != requestedRevision) { source.signOut(); throw CancellationException("query account changed") }
        try {
            val value = Jv.obj("username" to clean, "password" to secret)
            credentials.write(value.toString())
            check(Jv.parse(credentials.read().orEmpty()) == Jv(value)) { "查询凭据未能安全保存，请重试" }
            invalidate(); saved = Jv(value); username = clean; revised()
            reader = source; binding = "${owner.scope.value}|${owner.draftScope}|$revision"
        } catch (e: Exception) { source.signOut(); throw e }
    }

    suspend fun source(owner: Session): Session = mutex.withLock {
        if (username.isEmpty()) return@withLock owner
        val key = "${owner.scope.value}|${owner.draftScope}|$revision"
        reader?.takeIf { binding == key && it.signedIn }?.let { return@withLock it }
        invalidate()
        val secret = saved["password"].text.ifEmpty { if (username == BuildConfig.DEFAULT_QUERY_USERNAME) BuildConfig.DEFAULT_QUERY_PASSWORD else "" }
        require(secret.isNotEmpty()) { "请在独立查询账号设置中填写密码" }
        val source = connect(owner, username, secret)
        if (key != "${owner.scope.value}|${owner.draftScope}|$revision") { source.signOut(); throw CancellationException("query account changed") }
        reader = source; binding = key
        source
    }
}
