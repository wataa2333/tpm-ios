package com.personal.tpm.update

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class UpdateFile(val url: String, val size: Long, val sha256: String)
data class UpdateManifest(
    val versionCode: Long, val versionName: String, val packageName: String,
    val minSdk: Int, val notes: String, val apk: UpdateFile,
) {
    companion object {
        const val MAX_APK_SIZE = 1024L * 1024 * 1024
        fun parse(text: String, manifestUrl: String): UpdateManifest {
            val root = JSONObject(text)
            require(root.getInt("schemaVersion") == 1) { "不支持的更新清单格式" }
            fun file(obj: JSONObject): UpdateFile {
                val url = secureUrl(URI(manifestUrl).resolve(obj.getString("url")).toString())
                val size = obj.getLong("size")
                val sha = obj.getString("sha256").lowercase()
                require(size in 1..MAX_APK_SIZE && SHA.matches(sha)) { "更新文件大小或校验值无效" }
                return UpdateFile(url, size, sha)
            }
            val apk = file(root.getJSONObject("apk"))
            // Legacy manifests may contain patches; full updates deliberately ignore them.
            return UpdateManifest(
                root.getLong("versionCode"), root.getString("versionName"), root.getString("packageName"),
                root.getInt("minSdk"), root.optString("notes").take(8000), apk,
            ).also {
                require(it.versionCode > 0 && it.versionName.isNotBlank() && it.packageName.isNotBlank() && it.minSdk > 0) { "版本信息无效" }
            }
        }
    }
}

private val SHA = Regex("[0-9a-f]{64}")
fun secureUrl(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "更新地址必须为 HTTPS，不能包含账号或片段" }
    return uri.toASCIIString()
}

fun sha256(file: File, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
