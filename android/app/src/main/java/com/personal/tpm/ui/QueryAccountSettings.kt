package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.QueryAccounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalQueryAccounts = compositionLocalOf<QueryAccounts> { error("Query accounts not provided") }

@Composable
internal fun QueryAccountSettings() {
    val owner = LocalSession.current
    val queries = LocalQueryAccounts.current
    val tasks = rememberCoroutineScope()
    var name by remember(queries.username) { mutableStateOf(queries.username.ifEmpty { "C0382522" }) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("查询账号：${queries.username.ifEmpty { "当前登录账号" }}")
            Text("用于查询和看板的数据读取；工单操作仍使用当前登录账号。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("查询账号") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, enabled = !busy,
                placeholder = { if (queries.hasCredential(name)) Text("已配置，留空沿用") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            SoftButton(enabled = !busy && name.isNotBlank() && (password.isNotEmpty() || queries.hasCredential(name)), onClick = {
                busy = true; message = ""
                tasks.launch {
                    try { queries.configure(owner, name, password); password = ""; message = "登录及厂区已验证，查询账号已保存；接口权限以实际读取结果为准。" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "设置失败：${e.message ?: "未知错误"}" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在验证…" else "验证并保存") }
            TextButton(enabled = !busy && queries.username.isNotEmpty(), onClick = {
                try { queries.useOperator(); password = ""; message = "已删除独立查询凭据，改用当前登录账号" }
                catch (e: Exception) { message = e.message ?: "无法删除查询凭据" }
            }) { Text("使用当前登录账号并删除查询凭据") }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun QueryAccountSettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar("独立查询账号", onBack = onBack) }) { padding ->
        PageWidth { Column(Modifier.padding(padding).pageVerticalScroll(rememberScrollState()).padding(16.dp)) { QueryAccountSettings() } }
    }
}
