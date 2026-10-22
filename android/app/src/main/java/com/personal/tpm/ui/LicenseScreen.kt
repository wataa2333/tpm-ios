package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.personal.tpm.license.LicenseViewModel
import com.personal.tpm.license.MonthKey

/** Asks for the monthly key: 6 letters and digits, not case sensitive. */
@Composable
fun LicenseScreen(license: LicenseViewModel, reason: String) {
    val message by license.message.collectAsState()
    var text by remember { mutableStateOf("") }
    val submit = { if (text.length == MonthKey.LENGTH) license.submit(text) }
    StatusBarIcons(dark = true)
    PageWidth {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().elastic().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("输入本月密钥", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.TextField(colors = blockFieldColors(), shape = MaterialTheme.shapes.medium, 
            value = text,
            // Letters and digits only, shown in capitals, at most six.
            onValueChange = { text = MonthKey.normalize(it).take(MonthKey.LENGTH); license.clearMessage() },
            label = { Text("6 位密钥（不区分大小写）") },
            singleLine = true,
            isError = message != null,
            supportingText = { Text(message ?: "字母和数字，例如 A3B7K9") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = text.length == MonthKey.LENGTH, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("验证") }
        Spacer(Modifier.height(16.dp))
        Text("本 App 仅供学习交流使用，进入即代表同意。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        AppFooter()
    }
    }
}
