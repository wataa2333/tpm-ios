package com.personal.tpm.ui

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.personal.tpm.BuildConfig
import com.personal.tpm.data.OnlineApk
import com.personal.tpm.data.compareUpdateVersions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ApkMime = "application/vnd.android.package-archive"
private data class ApkTransfer(val id: Long, val file: File)

@Composable
internal fun OnlineApkDownload(update: OnlineApk, onClose: () -> Unit) {
    val context = LocalContext.current
    val downloads = remember(context) { context.getSystemService(DownloadManager::class.java) }
    var transfer by remember(update) { mutableStateOf<ApkTransfer?>(null) }
    var progress by remember(update) { mutableStateOf<Float?>(null) }
    var ready by remember(update) { mutableStateOf<Uri?>(null) }
    var error by remember(update) { mutableStateOf<String?>(null) }

    fun install(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ApkMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            error = "无法打开系统安装界面。"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) ready?.let(::install)
    }
    fun requestInstall(uri: Uri) {
        if (context.packageManager.canRequestPackageInstalls()) {
            install(uri)
        } else {
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null)))
            } catch (_: Exception) {
                error = "无法打开安装授权设置。"
            }
        }
    }

    LaunchedEffect(transfer) {
        val current = transfer ?: return@LaunchedEffect
        while (true) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    downloads.query(DownloadManager.Query().setFilterById(current.id)).use { cursor ->
                        check(cursor != null && cursor.moveToFirst())
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        check(status != DownloadManager.STATUS_FAILED)
                        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                        val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val archive = context.packageManager.getPackageArchiveInfo(current.file.path, 0)
                            require(archive != null && archive.packageName == context.packageName &&
                                archive.longVersionCode > BuildConfig.VERSION_CODE &&
                                compareUpdateVersions(archive.versionName.orEmpty(), update.version) == 0)
                            checkNotNull(downloads.getUriForDownloadedFile(current.id))
                        } else null
                        fraction to uri
                    }
                }.getOrNull()
            }
            if (result == null) {
                downloads.remove(current.id)
                error = "下载失败，或安装包与更新版本不匹配。请重新检查更新。"
                break
            }
            progress = result.first
            val uri = result.second
            if (uri != null) {
                ready = uri
                requestInstall(uri)
                break
            }
            delay(350)
        }
    }

    fun close() {
        if (ready == null) transfer?.let { downloads.remove(it.id) }
        onClose()
    }
    GlassAlertDialog(
        onDismissRequest = ::close,
        title = { Text("更新 " + update.version) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(update.name)
                when {
                    error != null -> Text(error.orEmpty())
                    ready != null -> Text("下载完成，可以覆盖安装。")
                    transfer != null -> {
                        val fraction = progress
                        Text(if (fraction == null) "正在下载 APK…" else "正在下载 " + (fraction * 100).toInt() + "%")
                        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text("在下方蓝奏云页面点下载，进度会显示在这里。")
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                            factory = { webContext ->
                                WebView(webContext).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            return request.url.scheme != "https"
                                        }
                                    }
                                    setDownloadListener { url, agent, _, _, _ ->
                                        if (transfer == null && error == null) {
                                            try {
                                                val uri = Uri.parse(url)
                                                require(uri.scheme == "https" && !uri.host.isNullOrBlank())
                                                val directory = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
                                                val file = File(directory, update.name)
                                                check(!file.exists() || file.delete())
                                                val request = DownloadManager.Request(uri)
                                                    .setTitle(update.name)
                                                    .setMimeType(ApkMime)
                                                    .setDestinationUri(Uri.fromFile(file))
                                                    .addRequestHeader("User-Agent", agent)
                                                    .addRequestHeader("Referer", this.url ?: update.link)
                                                CookieManager.getInstance().getCookie(url)?.let {
                                                    request.addRequestHeader("Cookie", it)
                                                }
                                                transfer = ApkTransfer(downloads.enqueue(request), file)
                                            } catch (_: Exception) {
                                                error = "无法开始下载，请重新检查更新。"
                                            }
                                        }
                                    }
                                    loadUrl(update.link)
                                }
                            },
                            onRelease = { it.destroy() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            ready?.let { uri ->
                GlassTextButton(onClick = { requestInstall(uri) }) { Text("安装") }
            }
        },
        dismissButton = {
            GlassTextButton(onClick = ::close) {
                Text(if (transfer != null && ready == null && error == null) "取消下载" else "关闭")
            }
        },
    )
}
