package com.personal.tpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.tpm.BuildConfig
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppVersionSettings() {
    var showing by remember { mutableStateOf(false) }
    SectionTitle("版本信息")
    GroupCard {
        ListItem(headlineContent = { Text("当前版本") }, supportingContent = { Text("${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        GroupDivider()
        ListItem(headlineContent = { Text("历史更新日志") }, supportingContent = { Text("随安装包提供，可离线查看") }, modifier = Modifier.clickable { showing = true }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
    AnimatedAppDialog(showing, { showing = false }) { ReleaseHistoryDialog { showing = false } }
}

@Composable
private fun ReleaseHistoryDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.applicationContext.assets
    // Stable window bounds: do not feed an animated wrap-content size back into a weighted lazy list.
    val dialogHeight=(LocalConfiguration.current.screenHeightDp*.78f).dp.coerceIn(240.dp,640.dp)
    var entries by remember { mutableStateOf<List<ReleaseEntry>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Set<String>>(setOf(BuildConfig.VERSION_NAME)) }
    LaunchedEffect(attempt) {
        loading = true; error = ""
        try {
            entries = withContext(Dispatchers.IO) {
                val names = assets.list("release-notes").orEmpty().filter { it.endsWith("-notes.txt") }
                require(names.isNotEmpty()) { "安装包未提供历史更新日志" }
                sortedReleaseHistory(names.map { name -> releaseEntry(name, assets.open("release-notes/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }) })
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取日志失败" }
        finally { loading = false }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().height(dialogHeight).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("历史更新日志", style = MaterialTheme.typography.headlineSmall)
            Text("点版本展开查看更新内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LoadingText("加载中")
            if (error.isNotBlank()) { Text(error); TextButton(onClick = { attempt++ }) { Text("重试") } }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.version }) { entry ->
                    GroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (entry.version in expanded) expanded - entry.version else expanded + entry.version }, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.version + if (entry.version == BuildConfig.VERSION_NAME) " · 当前版本" else "", style = MaterialTheme.typography.titleSmall)
                            Text(if (entry.version in expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        Reveal(entry.version in expanded) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                releaseNoteParagraphs(entry.notes).forEach { paragraph ->
                                    Text(paragraph, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp))
                                }
                            }
                        }
                    } }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}
