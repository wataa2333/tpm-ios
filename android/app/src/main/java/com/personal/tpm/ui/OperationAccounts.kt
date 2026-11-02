package com.personal.tpm.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OperationAccounts(private val context: Context, val owner: Session, private val vault: AccountVault) {
    private val selections = mutableStateMapOf<String, Session>()
    var requestingKey by mutableStateOf<String?>(null)
    fun request(key: String) { requestingKey = key }
    fun dismiss() { requestingKey = null }
    fun session(key: String): Session = selections[key] ?: owner
    fun clear(key: String) { selections.remove(key); if (requestingKey == key) dismiss() }
    fun retainOrders(keys: Set<String>) {
        selections.keys.toList().filter { (it.startsWith("Fault:") || it.startsWith("Erp:") || it.startsWith("quick:")) && it !in keys }.forEach(::clear)
    }
    suspend fun select(key: String, username: String) {
        if (username == owner.accountName) { clear(key); return }
        val originalScope = owner.scope.value
        val factory = owner.currentFactoryId
        val password = withContext(Dispatchers.IO) { vault.password(username) }
            ?: error("这个账号没有已保存的登录密码，请先登录并保存账号")
        val temporary = Session(context.applicationContext, transient = true)
        temporary.bindOperationOwner(owner)
        temporary.login(username, password)
        if (temporary.factories.isEmpty()) temporary.refreshFactories()
        if (temporary.factories.none { it["factoryId"].text == factory }) error("所选账号不能访问当前厂区")
        temporary.changeFactory(factory)
        if (owner.scope.value != originalScope || !owner.signedIn) throw CancellationException("主账号已改变")
        selections[key] = temporary
    }
}

val LocalOperationAccounts = compositionLocalOf<OperationAccounts?> { null }
val LocalOperationKey = compositionLocalOf<String?> { null }
val LocalOperationLocked = compositionLocalOf { false }

@Composable
fun OperationAccountScope(operationKey: String, content: @Composable () -> Unit) {
    val accounts = LocalOperationAccounts.current
    val session = accounts?.session(operationKey) ?: LocalSession.current
    CompositionLocalProvider(LocalSession provides session, LocalOperationKey provides operationKey, content = content)
}

@Composable
fun OperationAccountAction(locked: Boolean = false) {
    val accounts = LocalOperationAccounts.current ?: return
    val key = LocalOperationKey.current ?: return
    GlassIconButton(onClick = { accounts.request(key) }, enabled = !LocalOperationLocked.current && !locked) {
        Icon(Icons.Default.AccountCircle, "本次操作账号：${accounts.session(key).fullName.ifEmpty { accounts.session(key).accountName }}")
    }
}

@Composable
fun OperationAccountDialog() {
    val accounts = LocalOperationAccounts.current ?: return
    val key = accounts.requestingKey ?: return
    val vault = LocalVault.current
    var busy by remember(key) { mutableStateOf(false) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AppOptionSheet(true, { if (!busy) accounts.dismiss() }, "本次操作账号") {
        Text("只用于当前工单或整批操作，主界面账号保持不变。", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        val saved by vault.accounts.collectAsState()
        saved.forEach { account ->
            AppOptionItem(text = { Text(account.displayName.ifEmpty { account.username } + " · " + account.username) },
                selected = accounts.session(key).accountName == account.username, onClick = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try { accounts.select(key, account.username); accounts.dismiss() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure = e.message ?: "临时登录失败" }
                            finally { busy = false }
                        }
                    }
                })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    failure?.let { ErrorDialog("本次账号未更改", it) { failure = null } }
}
