package com.personal.tpm.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import com.personal.tpm.ui.theme.ThemeConfig
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.Session
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key

private enum class Screen { Login, Main }

/** Sign in, then open the app. Each change is a plain cross-fade. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(session: Session, vault: AccountVault, prefs: com.personal.tpm.data.PickerPrefs, drafts: com.personal.tpm.data.DraftStore, launchFinished: Boolean = true) {
    TransparentSystemBars()
    val auth by session.auth.collectAsState()
    val screen = when {
        auth["token"].text.isEmpty() -> Screen.Login
        else -> Screen.Main
    }
    val scope by session.scope.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val queries = androidx.compose.runtime.remember { com.personal.tpm.data.QueryAccounts(context).also { session.queryAccounts = it } }
    androidx.compose.runtime.LaunchedEffect(scope) { queries.invalidate() }
    // Scrolling containers own their platform overscroll effect; do not disable or duplicate it here.
    CompositionLocalProvider(
        LocalSession provides session, LocalVault provides vault, LocalPickerPrefs provides prefs, LocalDrafts provides drafts,
        LocalQueryAccounts provides queries,
        androidx.compose.material3.LocalRippleConfiguration provides if (AppPreferences.liquidGlass) null else androidx.compose.material3.LocalRippleConfiguration.current,
    ) {
        // The keyboard pushes the whole page up, so a text box that is being typed in is never under it.
        // The area of the screen the owner chose (see 我的 > 显示区域): the app lies inside it, the rest is plain background.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(
            Modifier.padding(start = ThemeConfig.displayStart.dp, top = ThemeConfig.displayTop.dp, end = ThemeConfig.displayEnd.dp, bottom = ThemeConfig.displayBottom.dp).fillMaxSize().imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Crossfade(targetState = screen, label = "root") { current ->
                when (current) {
                    Screen.Login -> LoginScreen(session, vault)
                    // The pages are rebuilt when the account or the factory changes (the key), like the iOS app.
                    Screen.Main -> Box(Modifier.fillMaxSize()) {
                        key(scope) { AppNav() }
                    }
                }
            }
        }
        }
        if (launchFinished) GlassIntroduction()
    }
}

/** What the first moment of the app shows instead of a blank page: the shape of the home page in grey, the light sweeping across it. */
@Composable
private fun StartupSkeleton() {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, bottom = 22.dp, top = 18.dp)) {
                Box(Modifier.width(120.dp).height(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)))
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            repeat(4) { SkeletonCard() }
        }
    }
}

private fun Modifier.statusBarsPadding(): Modifier = this.statusBarsPadding()

/**
 * The grey start-up skeleton stays laid over the app until its first frames are on screen, then fades away slowly (ease in and out), so the page
 * is revealed under it instead of replacing it in one cut. It lies over the app only for that moment and takes no touches.
 */
@Composable
private fun StartupCover() {
    var covering by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Two frames of the real page first (its first composition is the heavy one), then a short rest, then the fade.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(180)
        covering = false
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (covering) 1f else 0f, androidx.compose.animation.core.tween(Motion.Page, easing = Motion.Change), label = "cover",
    )
    if (alpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.background)) { StartupSkeleton() }
}
