package com.personal.tpm.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val status: Int? = null, val request: String? = null, cause: Throwable? = null) : Exception(message,cause)

/** One server filter, as the original client sends it. */
fun filter(field: String, value: String, operator: String = "eq"): JSONObject =
    Jv.obj("field" to field, "value" to value, "operator" to operator, "logic" to "and")

/**
 * The signed-in account: login, the token, the factory, and every request to the business server. Port of the iOS
 * `LiveSession`: the same addresses, the same Bearer token, the token renewed from the response header, answers that
 * arrive after the account or factory changed thrown away. The server speaks plain HTTP (see network_security_config).
 */
class Session(context: Context, private val queryOnly: Boolean = false, private val transient: Boolean = false) {
    private var operationOwner: Session? = null
    private var operationOwnerScope: Int = -1
    fun bindOperationOwner(owner: Session) { operationOwner = owner; operationOwnerScope = owner.scope.value }
    private fun checkOperationOwner() {
        operationOwner?.let { if (!it.signedIn || it.scope.value != operationOwnerScope) throw CancellationException("主账号或厂区已改变，本次操作已停止") }
    }
    private var fallbackOwner: Session? = null
    private var fallbackScope = -1
    private var fallbackActive = false
    private var onFallback: (() -> Unit)? = null
    internal fun configureReadFallback(owner: Session, onUse: () -> Unit) {
        check(queryOnly && !owner.queryOnly)
        fallbackOwner = owner; fallbackScope = owner.scope.value; onFallback = onUse
    }
    private fun checkedFallback(): Session = fallbackOwner?.takeIf {
        it.signedIn && it.scope.value == fallbackScope && it.currentFactoryId == currentFactoryId
    } ?: throw CancellationException("query owner changed")
    private suspend fun <T> withReadFallback(action: suspend (Session) -> T): T {
        if (queryOnly && fallbackActive) return action(checkedFallback())
        try { return action(this) } catch (e: ApiException) {
            if (!queryOnly || fallbackOwner == null || !queryFailureAllowsFallback(e)) throw e
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val owner = checkedFallback()
            if (!fallbackActive) { fallbackActive = true; onFallback?.invoke() }
            return action(owner)
        }
    }
    private val summaryContext = context.applicationContext
    val repairSummaryScope: String get() = "$draftScope|${queryAccounts?.username.orEmpty().ifEmpty { accountKey }}|repair-summary-faultTime-v3"
    private fun repairSummaryStore(processed: Boolean): SecureFile {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(repairSummaryScope.toByteArray()).joinToString("") { "%02x".format(it) }
        return SecureFile(summaryContext, "repair-summary-$digest-$processed.bin", "tpm_repair_summary_v1")
    }
    fun readRepairSummary(processed: Boolean): Jv = if(signedIn) runCatching { Jv.parse(repairSummaryStore(processed).read().orEmpty()) }.getOrDefault(Jv.NULL) else Jv.NULL
    fun writeRepairSummary(processed: Boolean, value: JSONObject) { if(signedIn) repairSummaryStore(processed).write(value.toString()) }
    var queryAccounts: QueryAccounts? = null
    val queryRevision: Int get() = queryAccounts?.revision ?: 0
    suspend fun readRequest(path: String, query: Map<String, String> = emptyMap(), body: Any? = null): Jv {
        val scope = _scope.value
        val revision = queryRevision
        val source = queryAccounts?.source(this) ?: this
        val result = source.request(path, query, body)
        if (scope != _scope.value || revision != queryRevision) throw CancellationException("query account changed")
        return result
    }
    suspend fun readPage(path: String, offset: Int = 0, filters: List<JSONObject> = emptyList(), limit: Int = 20): Jv =
        readRequest(path, body = Jv.obj("offset" to offset, "limit" to limit, "sort" to "createTime", "order" to "desc", "filters" to JSONArray(filters)))
    private val root = "http://tpm.forbetter.cn:8855/"
    private val store = SecureFile(context, "session.bin", "tpm_session_v1")
    private val prefs = context.applicationContext.getSharedPreferences("tpm_prefs", Context.MODE_PRIVATE)

    private val _auth = MutableStateFlow(Jv.NULL)
    val auth: StateFlow<Jv> = _auth.asStateFlow()
    /// Changes whenever the account or the factory changes: pages rebuild for the new scope.
    private val _scope = MutableStateFlow(0)
    val scope: StateFlow<Int> = _scope.asStateFlow()
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** Counts up whenever something was changed on the server (a task was taken forward): lists and details reload. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()
    fun notifyChanged() { _changed.value += 1; operationOwner?.notifyChanged() }

    init { if (!queryOnly && !transient) store.read()?.let { _auth.value = Jv.parse(it) } }

    val signedIn: Boolean get() = _auth.value["token"].text.isNotEmpty()
    private val token: String get() = _auth.value["token"].text
    val accountName: String get() = _auth.value["name"].text
    val userId: String get() = _auth.value["id"].text
    val fullName: String get() = _auth.value["fullName"].text
    val factories: List<Jv> get() = _auth.value["factories"].array
    val currentFactoryId: String get() = _auth.value["factoryId"].text
    val currentFactoryName: String get() = factories.firstOrNull { it["factoryId"].text == currentFactoryId }?.get("factoryName")?.text?.ifEmpty { null } ?: "选择厂区"

    /** Pins, defaults and drafts belong to one account in one factory, by ids that never change. */
    val accountKey: String get() {
        val id = _auth.value["id"].text
        return (if (id.isNotEmpty()) "id:$id" else accountName).lowercase()
    }
    val draftScope: String get() = "$accountKey#$currentFactoryId"
    /** The repair-result form and the ERP form keep their own local drafts, apart from the repair-report drafts. */
    val repairDraftScope: String get() = "$draftScope|repair"
    val erpDraftScope: String get() = "$draftScope|erp"

    private fun update(value: JSONObject) {
        _auth.value = Jv(value)
        if (!queryOnly && !transient) store.write(value.toString())
    }

    fun signOut() {
        fallbackOwner = null; fallbackActive = false; onFallback = null
        _auth.value = Jv.NULL
        if (!queryOnly && !transient) store.write(null)
        _scope.value += 1
    }

    suspend fun login(username: String, password: String) {
        fun escape(text: String) = URLEncoder.encode(text, "UTF-8")
        val result = send("userLogin", "POST", null, "username=${escape(username)}&password=${escape(password)}", form = true, signOutOnUnauthorized = false, authorized = false)
        val value = result as? JSONObject
        if (value == null || Jv(value)["token"].text.isEmpty()) throw ApiException("服务器没有返回有效登录会话", status = 401)
        update(value)
        _scope.value += 1
        if (!queryOnly && !transient) restoreLastFactory()
    }

    suspend fun refreshFactories() {
        val info = request("security/getMobileLoginUserInfo")
        val list = info["factories"].raw as? JSONArray ?: throw ApiException("服务器未返回厂区列表，请重试")
        val current = JSONObject(_auth.value.raw.toString())
        current.put("factories", list)
        update(current)
    }

    suspend fun changeFactory(factoryId: String) {
        if (factories.none { it["factoryId"].text == factoryId }) throw ApiException("请选择账号可访问的厂区")
        if (factoryId == currentFactoryId) return
        _switching.value = true
        try {
            val answer = send("rest/security/changeFactory?factoryId=" + URLEncoder.encode(factoryId, "UTF-8"), "POST", null, null, allowPlainText = true)
            val newToken = (answer as? String)?.trim().orEmpty()
            if (newToken.isEmpty()) throw ApiException("服务器未返回新厂区的登录令牌，请重新登录核对当前厂区")
            val current = JSONObject(_auth.value.raw.toString())
            current.put("token", newToken)
            current.put("factoryId", factories.firstOrNull { it["factoryId"].text == factoryId }?.get("factoryId")?.raw ?: factoryId)
            update(current)
            _scope.value += 1
            if (!queryOnly && !transient) prefs.edit().putString("lastFactory.$accountKey", currentFactoryId).apply()
        } finally { _switching.value = false }
    }

    /** After a sign-in the server picks its own default factory; go back to the one this account used last. */
    private suspend fun restoreLastFactory() {
        val last = prefs.getString("lastFactory.$accountKey", null)
        if (last != null && last != currentFactoryId && factories.any { it["factoryId"].text == last }) {
            try { changeFactory(last) } catch (e: Exception) { if (e is CancellationException) throw e }
        } else prefs.edit().putString("lastFactory.$accountKey", currentFactoryId).apply()
    }

    /** `body` is a JSONObject or JSONArray: with one the request is a POST of JSON. */
    suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: Any? = null): Jv {
        if (queryOnly && !precisionReadAllowed(path, query, body)) throw ApiException("独立查询会话仅允许精密度只读接口")
        val queryText = if (query.isEmpty()) "" else "?" + query.toSortedMap().entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val answer = withReadFallback { source -> source.send("rest/$path$queryText", if (body != null) "POST" else "GET", path, body?.toString(), signOutOnUnauthorized = !source.queryOnly || source.fallbackOwner == null) }
        return Jv(answer)
    }

    /** Uploads a photo (JPEG bytes) into [directory]; the answer describes the stored picture and goes into the form's `albums`. */
    suspend fun uploadPhoto(bytes: ByteArray, directory: String): Jv {
        if (queryOnly) throw ApiException("独立查询会话不能上传或操作单据")
        val boundary = "tpm" + System.currentTimeMillis()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return Jv(send("rest/upload/uploadImage?directory=" + URLEncoder.encode(directory, "UTF-8"), "POST", "upload", null, rawBody = head + bytes + tail, contentType = "multipart/form-data; boundary=$boundary"))
    }

    suspend fun page(path: String, offset: Int = 0, filters: List<JSONObject> = emptyList(), limit: Int = 20): Jv =
        request(path, body = Jv.obj("offset" to offset, "limit" to limit, "sort" to "createTime", "order" to "desc", "filters" to JSONArray(filters)))

    private suspend fun send(
        pathAndQuery: String, method: String, label: String?, body: String?,
        form: Boolean = false, allowPlainText: Boolean = false, signOutOnUnauthorized: Boolean = true, authorized: Boolean = true,
        rawBody: ByteArray? = null, contentType: String? = null,
    ): Any? = withContext(Dispatchers.IO) {
        checkOperationOwner()
        val scopeAtStart = _scope.value
        val connection = URL(root + pathAndQuery).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (form) { connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); connection.setRequestProperty("x-requested-with", "XMLHttpRequest") }
            else if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            else if (body != null) connection.setRequestProperty("Content-Type", "application/json")
            if (authorized) connection.setRequestProperty("Authorization", "Bearer $token")
            if (rawBody != null) { connection.doOutput = true; connection.outputStream.use { it.write(rawBody) } }
            else if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val status = try { connection.responseCode } catch (e: java.io.IOException) { throw ApiException("网络错误：" + (e.message ?: "连接失败"), request = label, cause = e) }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
            val text = bytes.toString(Charsets.UTF_8)
            // The page that asked may be gone by now (the account or the factory changed): its answer is thrown away.
            if (scopeAtStart != _scope.value) throw CancellationException("scope changed")
            checkOperationOwner()
            if (status !in 200..299) {
                if (status == 401 && signOutOnUnauthorized) signOut()
                val detail = errorSummary(text)
                val message = if (status == 403) (if (queryOnly) "当前查询账号没有读取权限（403）" else "当前登录账号没有访问或操作权限（403）") else "请求失败（$status）"
                throw ApiException(message + if (detail.isEmpty()) "" else "：$detail", status, label)
            }
            connection.getHeaderField("Authorization")?.let { header ->
                if (signedIn && authorized) {
                    val renewed = if (header.startsWith("Bearer ")) header.substring(7) else header
                    if (renewed.isNotEmpty() && renewed != token) update(JSONObject(_auth.value.raw.toString()).put("token", renewed))
                }
            }
            if (text.isBlank()) return@withContext null
            val parsed = try { org.json.JSONTokener(text).nextValue() } catch (e: Exception) { null }
            if (parsed is JSONObject || parsed is JSONArray) parsed
            else if (allowPlainText) text.trim() else parsed
        } finally {
            connection.disconnect()
        }
    }

    private fun errorSummary(text: String): String {
        val value = Jv.parse(text)
        for (key in listOf("message", "msg", "error", "detail", "error_description")) {
            val found = value[key].text
            if (found.isNotEmpty()) return found.take(200)
        }
        return if (value.isObject) "" else text.trim().take(200)
    }
}
