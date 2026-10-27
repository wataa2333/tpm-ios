package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.SavedAccount
import com.personal.tpm.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Just a title, the two fields and the button. If an account was remembered on this phone, its name is already in the field and
 * the remembered accounts are listed to sign in with one tap (after the phone's own check, as in the 我的 page). The password
 * is never shown: with the check switched off it is filled in the field as dots, otherwise it stays in the vault until a tap.
 */
@Composable
fun LoginScreen(session: Session, vault: AccountVault) {
    val context = LocalContext.current
    val remembered by vault.accounts.collectAsState()
    val latest = remember { vault.accounts.value.firstOrNull() }
    val prefillPassword = remember { latest?.takeIf { !BiometricGate.isEnabled(context) }?.let { vault.password(it.username) } }
    var username by remember { mutableStateOf(latest?.username.orEmpty()) }
    var password by remember { mutableStateOf(prefillPassword.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var keepPassword by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var signingInAs by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSignIn = !busy && username.isNotBlank() && password.isNotEmpty()

    StatusBarIcons(dark = true)
    val signIn = {
        if (canSignIn) {
            busy = true
            scope.launch {
                try {
                    session.login(username.trim(), password)
                    if (keepPassword) vault.save(username.trim(), password, session.fullName.ifEmpty { username.trim() })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "登录失败" }
                finally { busy = false }
            }
        }
    }
    fun signInWith(account: SavedAccount) {
        val activity = context.findActivity() ?: return
        if (busy) return
        busy = true; signingInAs = account.username
        scope.launch {
            try {
                when (val result = signInRemembered(activity, session, vault, account)) {
                    is RememberedSignIn.Failed -> error = result.message
                    else -> {}
                }
            } finally { busy = false; signingInAs = null }
        }
    }

    Box(Modifier.fillMaxSize()) {
    // The one loading animation of the app: a thin bar along the top edge.
    if (busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding())
    PageWidth {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("登录", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(32.dp))
            androidx.compose.material3.TextField(colors = blockFieldColors(), shape = MaterialTheme.shapes.medium, 
                value = username, onValueChange = { username = it }, label = { Text("账号") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextField(colors = blockFieldColors(), shape = MaterialTheme.shapes.medium, 
                value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, enabled = !busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { signIn() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showPassword) "隐藏密码" else "显示密码")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("记住密码", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = keepPassword, onCheckedChange = { keepPassword = it }, enabled = !busy)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { signIn() }, enabled = canSignIn, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                AnimatedLabel(if (busy && signingInAs == null) "登录中…" else "登录")
            }
            if (remembered.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text("已记住的账号", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = CardDefaults.cardElevation(0.dp)) {
                    remembered.forEachIndexed { index, account ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !busy) { signInWith(account) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { AnimatedLabel(account.displayName.ifEmpty { account.username }) },
                            supportingContent = { AnimatedLabel(account.username) },
                            leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                        if (index < remembered.lastIndex) GroupDivider()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            AppFooter()
        }
    }
    }
    error?.let { message -> ErrorDialog(title = "登录失败", message = message) { error = null } }
}
