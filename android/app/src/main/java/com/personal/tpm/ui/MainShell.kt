package com.personal.tpm.ui

import androidx.compose.material.icons.filled.Bolt

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.personal.tpm.data.OrderKind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import kotlin.math.roundToInt
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.tpm.data.SavedAccount
import com.personal.tpm.data.batchItem
import com.personal.tpm.data.Session
import com.personal.tpm.ui.theme.ThemeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private typealias Tab = GlassTab

/**
 * The five root tabs, including the repair template page. Material 3 parts: a big tinted title that folds into the top bar with the
 * finger, a page with rounded top corners, and the standard docked navigation bar (the system navigation bar under it is transparent).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    openList: (String) -> Unit, openPool: (PoolKind, String) -> Unit, openOrder: (OrderKind, String) -> Unit,
    openTextRepairQuery: () -> Unit,
    openWork: (WorkList) -> Unit, openQuery: (com.personal.tpm.data.FaultSearchField, String) -> Unit, openDrafts: () -> Unit, openNew: (FaultTemplate) -> Unit, openQuick: () -> Unit, openDev: () -> Unit, openDashboard: () -> Unit, openPrecision: () -> Unit, openMaintenanceStats: () -> Unit, openQuerySettings: () -> Unit, openPoolOrder: (PoolKind, String) -> Unit = { _, _ -> },
) {
    val session = LocalSession.current
    val auth by session.auth.collectAsState()
    val switching by session.switching.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var choosingFactory by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    val bar = rememberCollapsingBarState()
    val pull = rememberPullToRefreshState()
    var mineBusy by remember { mutableStateOf(false) }
    val homeVm = viewModel<HomeViewModel>(factory = viewModelFactory { initializer { HomeViewModel(session) } })
    val launchReady = LocalLaunchReady.current
    LaunchedEffect(homeVm.loading) {
        if (!homeVm.loading) { androidx.compose.runtime.withFrameNanos { }; launchReady() }
    }
    val todoVm = viewModel<TodoViewModel>(factory = viewModelFactory { initializer { TodoViewModel(session, autoStart = false) } })
    val workspaceVm = viewModel<WorkspaceViewModel>(factory = viewModelFactory { initializer { WorkspaceViewModel(session, autoStart = false) } })
    val launchFinished = LocalLaunchFinished.current
    LaunchedEffect(launchFinished, selected) {
        if (launchFinished) {
            // Badge refresh follows the reveal; workspace queries start only when that tab is needed.
            kotlinx.coroutines.delay(150)
            todoVm.ensureStarted()
            if (selected == 2) workspaceVm.ensureStarted()
        }
    }
    val gridState = rememberLazyGridState()
    val workspaceGrid = rememberLazyGridState()
    val todoState = rememberLazyListState()
    val mineTitle = auth["fullName"].text.ifEmpty { auth["name"].text.ifEmpty { "我的" } }
    val tabs = listOf(Tab("主页", Icons.Filled.Home), Tab("我的待办", Icons.Filled.Checklist), Tab("工作台", Icons.Filled.Dashboard), Tab(mineTitle, Icons.Filled.AccountCircle), Tab("新建报修", Icons.Filled.Add))
    val tabOrder = listOf(0, 1, 4, 2, 3)
    // Each tab starts with its big title open. Selecting orders belongs to the todo tab: leaving it ends the selection.
    LaunchedEffect(selected) { if (selected == 0) homeVm.onShown(); bar.expand(); if (selected != 1) todoVm.batch.end() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (selected == 0) homeVm.onShown() }
    val selectingTodos = selected == 1 && todoVm.batch.active
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(todoVm.batch.notice) { todoVm.batch.notice?.let { snackbar.showSnackbar(it); todoVm.batch.notice = null } }
    androidx.activity.compose.BackHandler(enabled = selectingTodos) { todoVm.batch.end() }

    /** The refresh button: back to the top with the title open, and the same refresh as pulling the page down. */
    fun refresh() {
        scope.launch {
            launch { bar.expand() }
            when (selected) { 0 -> gridState.animateScrollToItem(0); 1 -> todoState.animateScrollToItem(0); 2 -> workspaceGrid.animateScrollToItem(0) }
        }
        when (selected) { 0 -> homeVm.reload(pull = true); 1 -> todoVm.reload(pull = true); 2 -> workspaceVm.reload(pull = true) }
    }

    GlassMainFrame(
        topBar = {
            val working = switching || mineBusy ||
                (selected == 0 && (homeVm.loading || homeVm.refreshing)) || (selected == 1 && (todoVm.loading || todoVm.refreshing)) ||
                (selected == 2 && (workspaceVm.loading || workspaceVm.refreshing))
            val pulling = if ((selected == 0 && homeVm.refreshing) || (selected == 1 && todoVm.refreshing) || (selected == 2 && workspaceVm.refreshing)) 0f else if (selected <= 2) pull.distanceFraction else 0f
            CollapsingTopBar(
                title = if (selected == 3) "我的" else tabs[selected].title, state = bar, loading = working, pull = pulling,
                onTitleClick = null,
                actions = {
                    if (selected == 1) SelectButton(todoVm.batch.active) { if (todoVm.batch.active) todoVm.batch.end() else todoVm.batch.begin() }
                    // The factory: its name, and a tap to change it. Only on 主页 and 我的; the other pages work in the factory chosen there.
                    if (selected == 0 || selected == 3) GlassTextButton(onClick = { choosingFactory = true }, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                        AnimatedLabel(session.currentFactoryName, Modifier.widthIn(max = 140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换厂区")
                    }
                    if (selected <= 2) RefreshButton(spinning = working) { refresh() }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(visible = selected == 1 && !selectingTodos,
                enter = androidx.compose.animation.scaleIn(tween(Motion.Normal)) + androidx.compose.animation.fadeIn(tween(Motion.Normal)),
                exit = androidx.compose.animation.scaleOut(tween(Motion.Fast)) + androidx.compose.animation.fadeOut(tween(Motion.Fast))) {
                val colors = MaterialTheme.colorScheme
                val glass = AppPreferences.liquidGlass
                GlassActionButton(
                    onClick = openQuick, modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (glass) colors.primaryContainer else colors.primary,
                        contentColor = if (glass) colors.onPrimaryContainer else colors.onPrimary,
                    ),
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text("快速完成")
                }
            }
        },
        bottomBar = {
            // While todo tasks are being selected the tab bar gives way to the bar of the batch.
            androidx.compose.animation.AnimatedContent(
                targetState = selectingTodos, label = "bottomBar", modifier = if (AppPreferences.liquidGlass) Modifier else Modifier.fadeAbove(MaterialTheme.colorScheme.background).background(MaterialTheme.colorScheme.background),
                // Both bars sit on the bottom edge of the room they share (the tab bar is the lower one), so while the room grows or shrinks neither leaves a gap below it.
                contentAlignment = Alignment.BottomCenter,
                // The tab bar and the batch bar change places by a cross-fade while the room between them grows or shrinks (no slide: a slide down on top
                // of the shrinking room made the bar fall away).
                transitionSpec = { (androidx.compose.animation.fadeIn(tween(Motion.Slow, easing = Motion.Arrive)) togetherWith androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Leave))).using(androidx.compose.animation.SizeTransform(clip = true) { _, _ -> tween(Motion.Slow, easing = Motion.Change) }) },
            ) { selectingNow -> if (selectingNow) BatchBar(todoVm.batch) { todoVm.rows.mapNotNull { batchItem(it) } } else if (AppPreferences.liquidGlass) {
                GlassBottomBar(tabOrder.map(tabs::get), tabOrder.indexOf(selected), todoVm.badge) { position ->
                    val index = tabOrder[position]
                    if (selected != index) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); selected = index }
                }
            } else
            // The standard shadow of a bar at the bottom (3 dp) instead of a line above it.
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 0.dp) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                tabOrder.forEach { index ->
                    val tab = tabs[index]
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { if (selected != index) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); selected = index } },
                        icon = {
                            if (index == 1 && todoVm.badge > 0) BadgedBox(badge = { Badge(Modifier.offset(x = 9.dp)) { AnimatedLabel(if (todoVm.badge > 99) "99+" else todoVm.badge.toString()) } }) { Icon(tab.icon, contentDescription = null) }
                            else Icon(tab.icon, contentDescription = null)
                        },
                        label = { AnimatedLabel(tab.title, maxLines = 1) },
                    )
                }
            }
            }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RoundedSheet {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val nested = Modifier.nestedScroll(bar.connection)
                            androidx.compose.animation.Crossfade(targetState = selected, animationSpec = tween(Motion.Normal, easing = Motion.Change), label = "tabs") { tab ->
                            when (tab) {
                                0 -> HomeTab(homeVm, gridState, pull, nested, openList, openPool)
                                1 -> TodoTab(todoVm, todoState, pull, nested, openOrder, openPoolOrder)
                                2 -> WorkspaceTab(workspaceVm, workspaceGrid, pull, nested, openWork, openQuery, openDashboard, openPrecision, openMaintenanceStats, openTextRepairQuery)
                                3 -> MineTab(session, nested, onBusy = { mineBusy = it }, onChooseFactory = { choosingFactory = true }, openDrafts = openDrafts, openDev = openDev, openQuerySettings = openQuerySettings)
                                4 -> FaultTemplateTab(nested, openNew)
                                else -> Unit
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    BatchOverlays(todoVm.batch)
    if (choosingFactory) FactorySheet(
        session, onDismiss = { choosingFactory = false },
        onPick = { id ->
            choosingFactory = false
            scope.launch {
                try { session.changeFactory(id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { problem = "切换厂区失败" to (e.message ?: "切换厂区失败") }
            }
        },
    )
    problem?.let { (title, message) -> ErrorDialog(title, message) { problem = null } }
}

private val transparentRow @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun StepSlider(title: String, names: List<String>, selected: Int, onChange: (Int) -> Unit) {
    Column {
        AnimatedLabel(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassSlider(value = selected.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..names.lastIndex.toFloat(), steps = names.size - 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            names.forEachIndexed { index, name ->
                Text(
                    name, style = MaterialTheme.typography.labelMedium,
                    color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Setting by hand which part of the screen the app uses: four sliders for what is left unused at each edge. The change shows at once (the sheet
 * is a window of its own, so it stays where it is while the page behind moves), is saved, and is used at every start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayAreaSheet(onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("显示区域", style = MaterialTheme.typography.titleLarge)
            Text("拖动滑块，页面会立刻缩进；该边缘留出的部分不显示内容。设置会保存，下次打开直接使用。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun Edge(name: String, value: Int, set: (Int) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedLabel(name, Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall)
                    GlassSlider(value = value.toFloat(), onValueChange = { set(it.roundToInt()) }, valueRange = 0f..ThemeConfig.DISPLAY_AREA_MAX.toFloat(), modifier = Modifier.weight(1f))
                    AnimatedLabel("$value", Modifier.width(36.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Edge("上", ThemeConfig.displayTop) { ThemeConfig.setDisplayArea(top = it) }
            Edge("下", ThemeConfig.displayBottom) { ThemeConfig.setDisplayArea(bottom = it) }
            Edge("左", ThemeConfig.displayStart) { ThemeConfig.setDisplayArea(start = it) }
            Edge("右", ThemeConfig.displayEnd) { ThemeConfig.setDisplayArea(end = it) }
            SoftButton(onClick = { ThemeConfig.setDisplayArea(0, 0, 0, 0) }, Modifier.fillMaxWidth(), enabled = ThemeConfig.hasCustomArea) { Text("恢复默认") }
        }
    }
}

/** Choosing the factory: a bottom sheet with one rounded row per factory (an icon, the name), the current one filled and ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FactorySheet(session: Session, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("切换厂区", style = MaterialTheme.typography.titleLarge)
            AnimatedLabel("当前：" + session.currentFactoryName, Modifier.padding(top = 2.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.factories.forEach { factory ->
                    val id = factory["factoryId"].text
                    val current = id == session.currentFactoryId
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .then(if (AppPreferences.liquidGlass) Modifier.glassSurface(backdrop = null, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest, prominent = current)
                                else Modifier.background(if (current) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.colorScheme.primary, 0.22f) else MaterialTheme.colorScheme.surfaceContainerLowest))
                            .clickable { onPick(id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), Alignment.Center) {
                            Icon(Icons.Filled.Factory, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedLabel(factory["factoryName"].text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current) Icon(Icons.Filled.Check, contentDescription = "当前厂区", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MineTab(session: Session, nested: Modifier, onBusy: (Boolean) -> Unit, onChooseFactory: () -> Unit, openDrafts: () -> Unit, openDev: () -> Unit, openQuerySettings: () -> Unit) {
    val vault = LocalVault.current
    val context = LocalContext.current
    val auth by session.auth.collectAsState()
    val accounts by vault.accounts.collectAsState()
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var signingInAs by remember { mutableStateOf<String?>(null) }
    var pickingAccent by remember { mutableStateOf(false) }
    var adjustingArea by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = auth["fullName"].text.ifEmpty { auth["name"].text }
    val current = auth["name"].text

    // Signing in as another account runs the loading bar of the top bar.
    LaunchedEffect(signingInAs) { onBusy(signingInAs != null) }

    fun switchTo(account: SavedAccount) {
        val activity = context.findActivity() ?: return
        scope.launch {
            signingInAs = account.username
            try {
                when (val result = signInRemembered(activity, session, vault, account)) {
                    is RememberedSignIn.Failed -> error = "切换账号失败" to result.message
                    else -> {}
                }
            } finally { signingInAs = null }
        }
    }



    PageWidth {
        Column(
            nested.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Who is signed in: an avatar with the first letter, the name and the account.
            Card(
                Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
                        AnimatedLabel(name.take(1).ifEmpty { "?" }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column {
                        AnimatedLabel(name.ifEmpty { "—" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        AnimatedLabel("账号 $current", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            GroupCard {
                ListItem(
                    modifier = Modifier.clickable(onClick = onChooseFactory),
                    colors = transparentRow,
                    overlineContent = { Text("当前厂区") },
                    headlineContent = { AnimatedLabel(session.currentFactoryName) },
                    leadingContent = { Icon(Icons.Filled.Factory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "切换厂区") },
                )
            }

            GroupCard {
                val drafts = LocalDrafts.current
                var draftCount by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { draftCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { drafts.drafts(session.draftScope).size } }
                ListItem(
                    modifier = Modifier.clickable(onClick = openDrafts),
                    colors = transparentRow,
                    headlineContent = { AnimatedLabel(if (draftCount > 0) "草稿箱（$draftCount）" else "草稿箱") },
                    supportingContent = { Text("没填完的报修，保存在这台手机上") },
                    leadingContent = { Icon(Icons.Filled.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }

            SectionTitle("切换账号")
            GroupCard {
                accounts.forEach { account ->
                    val isCurrent = account.username == current
                    ListItem(
                        modifier = Modifier.clickable(enabled = !isCurrent && signingInAs == null) { switchTo(account) },
                        colors = transparentRow,
                        headlineContent = { AnimatedLabel(account.displayName.ifEmpty { account.username }) },
                        supportingContent = { AnimatedLabel(account.username) },
                        leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (isCurrent) Icon(Icons.Filled.Check, contentDescription = "当前账号", tint = MaterialTheme.colorScheme.primary)
                                GlassIconButton(onClick = { vault.remove(account.username) }) { Icon(Icons.Filled.Delete, contentDescription = "移除已记住的账号") }
                            }
                        },
                    )
                    GroupDivider()
                }
                if (accounts.isEmpty()) {
                    Text("还没有记住的账号。登录时打开“记住密码”，之后就可以在这里一键切换。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GroupDivider()
                }
                ListItem(
                    modifier = Modifier.clickable { session.signOut() },
                    colors = transparentRow,
                    headlineContent = { Text("添加其他账号") },
                    leadingContent = { Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }

            SectionTitle("外观")

            GroupCard {
                ListItem(
                    modifier = Modifier.clickable { pickingAccent = true },
                    colors = transparentRow,
                    headlineContent = { Text("主题色") },
                    supportingContent = { AnimatedLabel(if (ThemeConfig.randomOnLaunch) "每次打开随机；只用于重要信息" else (ThemeConfig.presets.firstOrNull { it.color == ThemeConfig.accent }?.name ?: "自定义") + "；只用于重要信息") },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Box(Modifier.size(28.dp).clip(CircleShape).background(ThemeConfig.effectiveAccent)) },
                )
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BlockSegmented(ThemeConfig.Mode.entries.map { it.title }, ThemeConfig.Mode.entries.indexOf(ThemeConfig.mode), { ThemeConfig.mode = ThemeConfig.Mode.entries[it] })
                }
            }

            GroupCard {
                ListItem(
                    modifier = Modifier.clickable { adjustingArea = true },
                    colors = transparentRow,
                    headlineContent = { Text("显示区域") },
                    supportingContent = { AnimatedLabel(if (ThemeConfig.hasCustomArea) "已自定义：上 ${ThemeConfig.displayTop}  下 ${ThemeConfig.displayBottom}  左 ${ThemeConfig.displayStart}  右 ${ThemeConfig.displayEnd}（dp）" else "屏幕边缘有损坏或被遮挡时，手动留出不用的部分") },
                    leadingContent = { Icon(Icons.Filled.Fullscreen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("界面大小", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                        // One tap applies it to the whole app, windows included: the Activity restarts once (a slider would restart it at every step).
                        BlockSegmented(ThemeConfig.sizeLevels, ThemeConfig.sizeLevel, { if (it != ThemeConfig.sizeLevel) { ThemeConfig.sizeLevel = it; activity?.recreate() } })
                    }
                    Text(
                        "界面会先按屏幕宽度自动适配，让不同的手机上看到的大小接近；这里的档位在此基础上再放大或缩小。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }



            SectionTitle("其他")
            GroupCard {
                ListItem(modifier = Modifier.clickable(onClick = openDev), colors = transparentRow,
                    headlineContent = { Text("开发者选项") },
                    leadingContent = { Icon(Icons.Filled.VerifiedUser, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
                GroupDivider()
                ListItem(modifier = Modifier.clickable(onClick = openQuerySettings), colors = transparentRow,
                    headlineContent = { Text("独立查询账号") },
                    leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
            }
            GroupCard {
                ListItem(
                    modifier = Modifier.clickable { session.signOut() },
                    colors = transparentRow,
                    headlineContent = { Text("退出登录", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                )
            }
            AppFooter()
            AppVersionSettings()
        }
    }

    if (pickingAccent) AccentSheet(onDismiss = { pickingAccent = false })
    if (adjustingArea) DisplayAreaSheet(onDismiss = { adjustingArea = false })
    error?.let { (title, message) -> ErrorDialog(title, message) { error = null } }
}

/**
 * The accent colour: the Material palette as round swatches, or any colour typed as hex; the depth slider makes it paler or deeper.
 * Choosing a colour applies it and closes the sheet; the slider changes the look live and leaves the sheet open.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccentSheet(onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val typed = hex.trim().removePrefix("#").takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        ?.let { Color(0xFF000000L or it.toLong(16)) }
    fun choose(color: Color) { ThemeConfig.setAccent(color); scope.launch { sheetState.hide(); onDismiss() } }
    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("主题色", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeConfig.presets.forEach { preset ->
                    Column(Modifier.width(64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { choose(preset.color) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(preset.color), Alignment.Center) {
                            if (preset.color == ThemeConfig.accent) Icon(Icons.Filled.Check, contentDescription = "当前主题色", tint = ThemeConfig.onColor(preset.color))
                        }
                        AnimatedLabel(preset.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("每次打开随机换色", style = MaterialTheme.typography.titleSmall)
                    Text("开启后，每次启动 App 从上面的颜色里随机选一个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassSwitch(checked = ThemeConfig.randomOnLaunch, onCheckedChange = { ThemeConfig.setRandomOnLaunch(it) })
            }
            SoftButton(onClick = { ThemeConfig.shuffleAccent() }, Modifier.fillMaxWidth()) { Text("随机换一个") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(colors = blockFieldColors(), shape = MaterialTheme.shapes.medium, 
                    value = hex, onValueChange = { hex = it.take(7) }, singleLine = true, modifier = Modifier.weight(1f).glassSearchSurface(MaterialTheme.shapes.medium),
                    label = { Text("自定义颜色（#RRGGBB）") }, prefix = { Text("#") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Box(Modifier.size(40.dp).clip(CircleShape).background(typed ?: MaterialTheme.colorScheme.surfaceContainerHigh))
                GlassActionButton(onClick = { typed?.let { choose(it) } }, enabled = typed != null) { Text("使用") }
            }
        }
    }
}
