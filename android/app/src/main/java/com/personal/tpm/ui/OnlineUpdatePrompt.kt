package com.personal.tpm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.tpm.BuildConfig
import com.personal.tpm.data.AndroidUpdateFolder
import com.personal.tpm.data.OnlineApk
import com.personal.tpm.data.compareUpdateVersions
import com.personal.tpm.data.readOnlineAndroidUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

val LocalOnlineUpdate = compositionLocalOf<OnlineUpdateState?> { null }

class OnlineUpdateState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("android-online-update", Context.MODE_PRIVATE)
    private var checkedAtLaunch = false
    var checking by mutableStateOf(false)
        private set
    var candidate by mutableStateOf<OnlineApk?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    suspend fun check(manual: Boolean = false) {
        if (checking || (!manual && checkedAtLaunch)) return
        if (!manual) checkedAtLaunch = true
        checking = true
        message = null
        try {
            val latest = withTimeoutOrNull(15_000) { Result.success(readOnlineAndroidUpdate()) }
                ?: error("检查更新超时")
            val update = latest.getOrThrow()
            if (update == null || compareUpdateVersions(update.version, BuildConfig.VERSION_NAME) <= 0) {
                if (manual) message = "分享目录中没有比当前版本更新的安装包。"
                return
            }
            val skipped = prefs.getString("skipped", null)
            if (manual || skipped != update.version) candidate = update
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (manual) message = e.message ?: "暂时无法检查更新。"
        } finally {
            checking = false
        }
    }

    fun skip(version: String) {
        prefs.edit().putString("skipped", version).apply()
        dismiss()
    }

    fun dismiss() { candidate = null; message = null }
}

@Composable
fun OnlineUpdatePrompt(state: OnlineUpdateState) {
    val context = LocalContext.current
    val launchFinished = LocalLaunchFinished.current
    LaunchedEffect(state, launchFinished) {
        if (launchFinished) state.check()
    }
    fun open(link: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        catch (_: android.content.ActivityNotFoundException) { return }
        state.dismiss()
    }
    var downloading by remember { mutableStateOf<OnlineApk?>(null) }
    downloading?.let { apk ->
        OnlineApkDownload(apk, onClose = { downloading = null })
        return
    }
    val update = state.candidate
    if (update != null) {
        GlassAlertDialog(
            onDismissRequest = state::dismiss,
            title = { Text("发现新版本 ${update.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}")
                    Text(update.name)
                    Text("在应用内下载完整 APK，完成后打开系统安装界面。")
                }
            },
            confirmButton = { GlassTextButton(onClick = { downloading = update; state.dismiss() }) { Text("下载") } },
            dismissButton = { GlassTextButton(onClick = { state.skip(update.version) }) { Text("跳过") } },
        )
    } else {
        state.message?.let { message ->
            GlassAlertDialog(
                onDismissRequest = state::dismiss,
                title = { Text("检查更新") },
                text = { Text(message) },
                confirmButton = { GlassTextButton(onClick = { open(AndroidUpdateFolder) }) { Text("打开下载页") } },
                dismissButton = { GlassTextButton(onClick = state::dismiss) { Text("关闭") } },
            )
        }
    }
}
