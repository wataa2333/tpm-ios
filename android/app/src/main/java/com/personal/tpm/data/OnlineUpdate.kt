package com.personal.tpm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

const val AndroidUpdateFolder = "https://wataa.lanzoum.com/s/wataa2333"

private val versionPattern = Regex("""[0-9]{1,9}(?:\.[0-9]{1,9}){2,5}""")
private val apkPattern = Regex("""wataa-([0-9]{1,9}(?:\.[0-9]{1,9}){2,5})\.apk""", RegexOption.IGNORE_CASE)
private val shareId = Regex("""[a-zA-Z0-9_-]{1,80}""")
private val folderEndpoint by lazy { Regex("""url\s*:\s*'(/filemoreajax\.php\?file=\d+)'\s*,\s*data\s*:\s*\{([^\}]+)\}""") }
private val scriptVariable = Regex("""\bvar\s+(\w+)\s*=\s*'([^']*)'""")
private val formValue = Regex("""'(\w+)'\s*:\s*('(?:[^']*)'|\w+)""")
private val formKeys = setOf("lx", "fid", "uid", "puid", "pg", "rep", "t", "k", "up", "vip", "webfoldersign")

data class OnlineApk(val version: String, val name: String, val link: String)

internal fun compareUpdateVersions(first: String, second: String): Int {
    require(versionPattern.matches(first) && versionPattern.matches(second))
    val a = first.split('.').map(String::toInt)
    val b = second.split('.').map(String::toInt)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun latestOnlineApk(entries: List<Jv>): OnlineApk? = entries.mapNotNull { row ->
    val name = row["name_all"].text.trim()
    val version = apkPattern.matchEntire(name)?.groupValues?.get(1) ?: return@mapNotNull null
    val id = row["id"].text
    if (id == "-1" || !shareId.matches(id)) return@mapNotNull null
    OnlineApk(version, name, URI(AndroidUpdateFolder).resolve("/$id").toString())
}.maxWithOrNull { a, b -> compareUpdateVersions(a.version, b.version) }

internal data class UpdateFolderForm(val path: String, val values: Map<String, String>)

internal fun readUpdateFolderForm(html: String): UpdateFolderForm {
    val match = folderEndpoint.find(html) ?: error("蓝奏云分享页格式已变化或暂不可读")
    val variables = scriptVariable.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
    val values = formValue.findAll(match.groupValues[2]).mapNotNull { field ->
        val (_, key, raw) = field.groupValues
        if (key !in formKeys) return@mapNotNull null
        val value = when {
            key == "pg" -> "1"
            raw.startsWith("'") -> raw.removeSurrounding("'")
            raw.all(Char::isDigit) -> raw
            else -> variables[raw] ?: error("蓝奏云分享页缺少列表参数")
        }
        key to value
    }.toMap()
    require(setOf("lx", "fid", "uid", "puid", "pg", "t", "k").all { !values[it].isNullOrBlank() }) {
        "蓝奏云分享页缺少列表参数"
    }
    return UpdateFolderForm(match.groupValues[1], values)
}

suspend fun readOnlineAndroidUpdate(): OnlineApk? = withContext(Dispatchers.IO) {
    val folder = URI(AndroidUpdateFolder)
    val cookies = CookieManager()
    val form = readUpdateFolderForm(fetchUpdatePage(folder, cookies))
    val listUri = folder.resolve(form.path)
    var latest: OnlineApk? = null
    for (page in 1..20) {
        ensureActive()
        val values = form.values + ("pg" to page.toString())
        val body = values.entries.joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val response = Jv.parse(fetchUpdatePage(listUri, cookies, body))
        when (response["zt"].text) {
            "4" -> return@withContext latest
            "1" -> {
                val rows = response["text"]
                require(rows.raw is JSONArray) { "蓝奏云文件列表格式异常" }
                if (rows.array.isEmpty()) return@withContext latest
                val candidate = latestOnlineApk(rows.array)
                if (candidate != null && (latest == null || compareUpdateVersions(candidate.version, latest.version) > 0)) {
                    latest = candidate
                }
            }
            else -> error("蓝奏云暂时无法读取文件列表")
        }
    }
    error("蓝奏云文件列表过长，未完成检查")
}

private fun fetchUpdatePage(uri: URI, cookies: CookieManager, body: String? = null): String {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        cookies.get(uri, emptyMap()).forEach { (key, values) -> values.forEach { connection.addRequestProperty(key, it) } }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Referer", AndroidUpdateFolder)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        require(connection.responseCode in 200..299) { "蓝奏云请求失败（HTTP ${connection.responseCode}）" }
        cookies.put(uri, connection.headerFields)
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 512_000) { "蓝奏云响应过大" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    } finally {
        connection.disconnect()
    }
}
