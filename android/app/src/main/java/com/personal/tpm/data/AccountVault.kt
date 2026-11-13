package com.personal.tpm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedAccount(val username: String, val displayName: String, val lastUsed: Long)

/**
 * Remembered accounts: the login page can keep the password of an account, and the 我的 page switches between the
 * remembered accounts without typing. They live in an AES-GCM encrypted file whose key is in the Android Keystore
 * (never in backups, never shown on screen: the lists show the account and the name, not the password).
 */
class AccountVault(context: Context) {
    private val file = SecureFile(context, "accounts.bin", "tpm_accounts_v1")
    private val _accounts by lazy { MutableStateFlow(readList()) }
    val accounts: StateFlow<List<SavedAccount>> by lazy { _accounts.asStateFlow() }

    private fun readArray(): JSONArray = try { JSONArray(file.read() ?: "[]") } catch (e: Exception) { JSONArray() }
    private fun readList(): List<SavedAccount> = readArray().let { array ->
        List(array.length()) { array.getJSONObject(it) }
            .map { SavedAccount(it.optString("u"), it.optString("n"), it.optLong("t")) }
            .filter { it.username.isNotEmpty() }
            .sortedByDescending { it.lastUsed }
    }

    fun password(username: String): String? {
        val array = readArray()
        for (i in 0 until array.length()) array.getJSONObject(i).takeIf { it.optString("u") == username }?.let { return it.optString("p").ifEmpty { null } }
        return null
    }

    fun save(username: String, password: String, displayName: String) {
        val array = readArray()
        val kept = JSONArray()
        for (i in 0 until array.length()) if (array.getJSONObject(i).optString("u") != username) kept.put(array.get(i))
        kept.put(JSONObject().put("u", username).put("p", password).put("n", displayName).put("t", System.currentTimeMillis()))
        file.write(kept.toString())
        _accounts.value = readList()
    }

    fun remove(username: String) {
        val array = readArray()
        val kept = JSONArray()
        for (i in 0 until array.length()) if (array.getJSONObject(i).optString("u") != username) kept.put(array.get(i))
        file.write(if (kept.length() == 0) null else kept.toString())
        _accounts.value = readList()
    }
}
