package com.personal.tpm.ui

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import com.personal.tpm.ui.theme.ThemeConfig
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.personal.tpm.data.AccountVault
import com.personal.tpm.data.QueryAccounts
import com.personal.tpm.data.Session
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import com.personal.tpm.TpmApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Screen { Login, Main }
internal val LocalStartupComplete = compositionLocalOf { true }
private const val StartupSkeletonMillis = 250L
private const val StartupRenderBufferMillis = 100L

@Composable
fun AppRoot(app: TpmApp) {
    var services by remember(app) { mutableStateOf<Pair<Session, QueryAccounts>?>(null) }
    var showSkeleton by remember { mutableStateOf(true) }
    val firstSkeletonDraw = remember { CompletableDeferred<Unit>() }
    val firstAppDraw = remember { CompletableDeferred<Unit>() }
    val skeletonAlpha = remember { Animatable(1f) }

    LaunchedEffect(app) {
        firstSkeletonDraw.await()
        withFrameNanos { }
        delay(StartupSkeletonMillis)
        services = withContext(Dispatchers.IO) { app.session to QueryAccounts(app) }
        firstAppDraw.await()
        withFrameNanos { }
        delay(StartupRenderBufferMillis)
        skeletonAlpha.animateTo(0f, tween(Motion.Fast, easing = Motion.Leave))
        showSkeleton = false
        withContext(Dispatchers.IO) { app.drafts.warmUp() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        services?.let { (session, queries) ->
            Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (!firstAppDraw.isCompleted) firstAppDraw.complete(Unit)
            }) {
                CompositionLocalProvider(LocalStartupComplete provides !showSkeleton) {
                    AppContent(session, app.vault, app.pickerPrefs, app.drafts, queries)
                }
            }
        }
        if (showSkeleton) {
            androidx.activity.compose.BackHandler { }
            HomeStartupSkeleton(
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { alpha = skeletonAlpha.value }
                    .drawWithContent {
                        drawContent()
                        if (!firstSkeletonDraw.isCompleted) firstSkeletonDraw.complete(Unit)
                    }.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
                animate = ValueAnimator.areAnimatorsEnabled(),
            )
        }
    }
}

/** Sign in, then open the app. Each change is a plain cross-fade. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(session: Session, vault: AccountVault, prefs: com.personal.tpm.data.PickerPrefs,
    drafts: com.personal.tpm.data.DraftStore, queries: QueryAccounts) {
    TransparentSystemBars()
    val auth by session.auth.collectAsState()
    val screen = when {
        auth["token"].text.isEmpty() -> Screen.Login
        else -> Screen.Main
    }
    val scope by session.scope.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = androidx.compose.runtime.remember(context) { OnlineUpdateState(context) }
    androidx.compose.runtime.SideEffect { session.queryAccounts = queries }
    LaunchedEffect(scope) { queries.invalidate() }
    // Scrolling containers own their platform overscroll effect; do not disable or duplicate it here.
    CompositionLocalProvider(
        LocalSession provides session, LocalVault provides vault, LocalPickerPrefs provides prefs, LocalDrafts provides drafts,
        LocalQueryAccounts provides queries,
        LocalOnlineUpdate provides updates,
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
        if (LocalStartupComplete.current) OnlineUpdatePrompt(updates)
    }
}
