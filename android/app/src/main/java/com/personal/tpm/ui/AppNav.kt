package com.personal.tpm.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.personal.tpm.data.FaultSearchField
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.faultStateName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val TABS = "tabs"
private const val LIST = "list/{state}?q={q}&f={f}&assetCode={assetCode}"
private const val FAULT = "fault/{id}?independent={independent}"
private const val ERP = "erp/{id}?independent={independent}"
private const val FLOW = "flow/{id}"
private const val POOL = "pool/{kind}?state={state}"
private const val PAGED = "paged/{type}"
private const val TASK = "task/{kind}/{id}?ret={ret}"
private const val FAULT_FORM = "form/fault?id={id}&draft={draft}&template={template}&asset={asset}&batch={batch}&codes={codes}"
private const val ASSET_FAULT_TEMPLATES = "report-templates?asset={asset}"
private const val FAULT_TEMPLATE_EDITOR = "report-template-editor?id={id}"
private const val ERP_FORM = "form/erp?id={id}"
private const val REPAIR = "repair/{id}"
private const val DRAFTS = "drafts"
private const val COMPLETED_ORDERS = "completed-orders"
private const val DEV = "dev"

private fun newFaultRoute(id: String = "", draft: String = "", template: String = "", asset: String = "", batch: Boolean = false, codes: List<String> = emptyList()) =
    "form/fault?id=" + Uri.encode(id) + "&draft=" + Uri.encode(draft) +
        "&template=" + Uri.encode(template) + "&asset=" + Uri.encode(asset) + "&batch=" + batch +
        "&codes=" + if (codes.isEmpty()) "" else Uri.encode(org.json.JSONArray(codes).toString())

/**
 * The screens above the tabs. Each slides in from the right and back out on an ease-in-out curve (half a second); the tab bar is only on the
 * tabs, and a list that was left to open an order is found as it was (rows, scroll, expanded cards) when coming back.
 */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val session = LocalSession.current
    var taskRefreshPending by remember { mutableStateOf(false) }
    LaunchedEffect(taskRefreshPending, nav) {
        if (taskRefreshPending) {
            nav.visibleEntries.first { entries ->
                entries.singleOrNull()?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            delay(Motion.Instant.toLong())
            taskRefreshPending = false
            session.notifyChanged()
        }
    }
    fun editFaultTemplate(id: String) {
        nav.navigate("report-template-editor?id=" + Uri.encode(id))
    }
    fun openAssetReports(code: String) {
        nav.navigate("list/_?assetCode=" + Uri.encode(code))
    }
    fun openSelectedReports(codes: List<String>) {
        nav.navigate(newFaultRoute(batch = true, codes = codes))
    }
    fun reportAsset(asset: com.personal.tpm.data.Jv) {
        val reference = com.personal.tpm.data.Jv.obj(
            "assetId" to asset["assetId"].text, "assetCode" to asset["assetCode"].text, "assetName" to asset["assetName"].text,
        )
        nav.navigate("report-templates?asset=" + Uri.encode(reference.toString()))
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val vault = LocalVault.current
    val pickerPrefs = LocalPickerPrefs.current
    val drafts = LocalDrafts.current
    val operators = remember(session.draftScope) { OperationAccounts(context, session, vault) }
    val quick = viewModel<QuickFinishViewModel>(key = "quick-${session.draftScope}-${session.scope.value}",
        factory = viewModelFactory { initializer { QuickFinishViewModel(session, pickerPrefs, drafts) } })
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStack.collect { stack ->
            val keys = stack.mapNotNull { entry ->
                val route = entry.destination.route.orEmpty()
                val id = entry.arguments?.getString("id").orEmpty()
                when {
                    route == "quick-finish" -> quick.operationKey
                    route == TASK -> entry.arguments?.getString("kind").orEmpty() + ":" + id
                    route == FAULT || route == FAULT_FORM || route == REPAIR -> "Fault:" + id
                    route == ERP || route == ERP_FORM -> "Erp:" + id
                    else -> null
                }
            }.toSet()
            operators.retainOrders(keys + quick.operationKey)
        }
    }
    androidx.compose.runtime.LaunchedEffect(quick.completionAcknowledged, quick.busy) {
        if (quick.completionAcknowledged && !quick.busy) operators.clear(quick.operationKey)
    }
    // A page change takes Motion.Page; what arrives decelerates, what leaves eases in and out.
    val duration = Motion.Page
    androidx.compose.runtime.CompositionLocalProvider(LocalOperationAccounts provides operators,
        LocalQuickProgress provides QuickProgress(quick.badge, { quick.showProgress = true })) {
    NavHost(
        navController = nav, startDestination = TABS,
        // Slides only, no fade: a fade makes the phone draw the whole page (a long form, a list of cards) into an off-screen buffer and blend it again
        // at every frame of the half second, and that is what dropped frames on the heavy pages. A slide just moves the page that is already drawn.
        enterTransition = { slideInHorizontally(tween(duration, easing = Motion.Arrive)) { it } },
        exitTransition = { slideOutHorizontally(tween(duration, easing = Motion.Leave)) { -it / 4 } },
        popEnterTransition = { slideInHorizontally(tween(duration, easing = Motion.Arrive)) { -it / 4 } },
        popExitTransition = { slideOutHorizontally(tween(duration, easing = Motion.Leave)) { it } },
    ) {
        composable(TABS) {
            run {
                MainShell(
                    openList = { state -> nav.navigate("list/" + Uri.encode(state.ifEmpty { "_" }) + "?q=&f=") },
                    openPool = { kind, state -> nav.navigate("pool/" + kind.path + "?state=" + Uri.encode(state)) },
                    openMaintenanceStats = { nav.navigate("maintenance-statistics") },
                    openPoolOrder = { kind, id -> nav.navigate("pool-order/${kind.path}/" + Uri.encode(id)) },
                    openOrder = { kind, id -> nav.navigate((if (kind == OrderKind.Erp) "erp/" else "fault/") + Uri.encode(id)) },
                    openWork = { list -> nav.navigate("paged/" + list.key) },
                    openTextRepairQuery = { nav.navigate("repair-text-query") },
                    openQuery = { field, text -> nav.navigate("list/_?q=" + Uri.encode(text) + "&f=" + field.name) },
                    openDrafts = { nav.navigate(DRAFTS) },
                    openCompletedOrders = { nav.navigate(COMPLETED_ORDERS) { launchSingleTop = true } },
                    openNew = { template ->
                        nav.navigate(newFaultRoute(template = template.id))
                    },
                    openBatchReport = { template -> nav.navigate(newFaultRoute(template = template.id, batch = true)) },
                    editFaultTemplate = ::editFaultTemplate,
                    openAssetReports = ::openAssetReports,
                    reportAsset = ::reportAsset,
                    openSelectedReports = ::openSelectedReports,
                    openQuick = {
                        if (quick.loading || quick.signing) quick.showProgress = true
                        else { operators.clear(quick.operationKey); quick.discover() }
                    },
                    openDev = { nav.navigate(DEV) { launchSingleTop = true } },

                    openDashboard = { nav.navigate("management") },
                    openPrecision = { nav.navigate("precision-dashboard") },
                    openQuerySettings = { nav.navigate("query-account-settings") },
                )
            }
        }
        composable("repair-text-query") {
            RepairTextQueryScreen(
                onBack = { nav.popBackStack() },
                openOrder = { id -> nav.navigate("fault/" + Uri.encode(id) + "?independent=true") },
            )
        }
        composable(
            LIST,
            arguments = listOf(
                navArgument("state") { type = NavType.StringType },
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                navArgument("f") { type = NavType.StringType; defaultValue = "" },
                navArgument("assetCode") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry: NavBackStackEntry ->
            run {
                val state = entry.arguments?.getString("state").orEmpty().let { if (it == "_") "" else it }
                val query = entry.arguments?.getString("q").orEmpty()
                val field = FaultSearchField.entries.firstOrNull { it.name == entry.arguments?.getString("f") } ?: FaultSearchField.Name
                val assetCode = entry.arguments?.getString("assetCode").orEmpty()
                val vm = viewModel<OrderListViewModel>(factory = viewModelFactory {
                    initializer { OrderListViewModel(session, state, query, field, assetCode) }
                })
                OrderListScreen(
                    vm = vm,
                    // A quick-query page is titled by what it searches now, following each confirmed search and the chosen field.
                    title = when {
                        assetCode.isNotEmpty() -> "资产报修记录"
                        vm.searchMode -> vm.field.label + " " + vm.query
                        vm.state.isEmpty() -> "所有工单"
                        else -> faultStateName(vm.state)
                    },
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("fault/" + Uri.encode(id) + "?independent=" + vm.searchMode) },
                    reportAsset = ::reportAsset,
                    assetReports = if (assetCode.isBlank()) ::openAssetReports else null,
                    reportSelected = ::openSelectedReports,
                )
            }
        }
        composable(FAULT, arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("independent") { type = NavType.BoolType; defaultValue = false })) { entry ->
            OperationAccountScope("Fault:" + entry.arguments?.getString("id").orEmpty()) {
            run {
                val id = entry.arguments?.getString("id").orEmpty()
                FaultDetailScreen(
                    id = id, independent = entry.arguments?.getBoolean("independent") == true, onBack = { nav.popBackStack() }, onOpenFlow = { nav.navigate("flow/" + Uri.encode(it)) },
                    onOpenTask = { returning -> nav.navigate("task/Fault/" + Uri.encode(id) + "?ret=" + returning) },
                    onEdit = { nav.navigate(newFaultRoute(id)) }, onOpenRepair = { nav.navigate("repair/" + Uri.encode(id)) },
                    onReportAsset = ::reportAsset,
                    onAssetReports = ::openAssetReports,
                )
            }
                    }
        }
        composable(ERP, arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("independent") { type = NavType.BoolType; defaultValue = false })) { entry ->
            OperationAccountScope("Erp:" + entry.arguments?.getString("id").orEmpty()) {
            run {
                val id = entry.arguments?.getString("id").orEmpty()
                ErpDetailScreen(
                    id = id, independent = entry.arguments?.getBoolean("independent") == true, onBack = { nav.popBackStack() }, onOpenFlow = { nav.navigate("flow/" + Uri.encode(it)) },
                    onOpenTask = { returning -> nav.navigate("task/Erp/" + Uri.encode(id) + "?ret=" + returning) },
                    onEdit = { nav.navigate("form/erp?id=" + Uri.encode(id)) },
                )
            }
                    }
        }
        composable(
            TASK,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
                navArgument("ret") { type = NavType.StringType; defaultValue = "false" },
            ),
        ) { entry ->
            OperationAccountScope(entry.arguments?.getString("kind").orEmpty() + ":" + entry.arguments?.getString("id").orEmpty()) {
            val kind = if (entry.arguments?.getString("kind") == "Erp") OrderKind.Erp else OrderKind.Fault
            CompactTheme { TaskScreen(
                kind = kind, id = entry.arguments?.getString("id").orEmpty(), startReturn = entry.arguments?.getString("ret") == "true",
                onBack = { nav.popBackStack() },
                // After a step is taken: back to where the order was opened from, except signing in, which stays on the order.
                onDone = { stay ->
                    val detailRoute = if (kind == OrderKind.Fault) FAULT else ERP
                    if (stay || nav.previousBackStackEntry?.destination?.route != detailRoute) nav.popBackStack()
                    else nav.popBackStack(detailRoute, inclusive = true)
                    operators.clear(kind.toString() + ":" + entry.arguments?.getString("id").orEmpty())
                    taskRefreshPending = true
                },
                refreshOnDone = false,
            ) }
                    }
        }
        composable(ASSET_FAULT_TEMPLATES, arguments = listOf(navArgument("asset") { type = NavType.StringType })) { entry ->
            val asset = entry.arguments?.getString("asset").orEmpty()
            AssetFaultTemplateScreen(com.personal.tpm.data.Jv.parse(asset), onBack = { nav.popBackStack() }, edit = ::editFaultTemplate) { template ->
                nav.navigate(newFaultRoute(template = template.id, asset = asset))
            }
        }
        composable(FAULT_TEMPLATE_EDITOR, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { entry ->
            FaultTemplateEditorScreen(entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
        composable(
            FAULT_FORM,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("draft") { type = NavType.StringType; defaultValue = "" },
                navArgument("template") { type = NavType.StringType; defaultValue = "" },
                navArgument("asset") { type = NavType.StringType; defaultValue = "" },
                navArgument("batch") { type = NavType.BoolType; defaultValue = false },
                navArgument("codes") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val batch = entry.arguments?.getBoolean("batch") == true
            val form: @Composable () -> Unit = {
                FaultFormScreen(
                    existingId = id.ifEmpty { null },
                    startDraftId = entry.arguments?.getString("draft").orEmpty().ifEmpty { null },
                    onClose = { nav.popBackStack() }, onSaved = {},
                    initialAsset = entry.arguments?.getString("asset")?.takeIf { it.isNotEmpty() }?.let { com.personal.tpm.data.Jv.parse(it) },
                    batchMode = batch,
                    initialAssetCodes = com.personal.tpm.data.Jv.parse(entry.arguments?.getString("codes").orEmpty()).array.map { it.text },
                    template = when (val templateId = entry.arguments?.getString("template").orEmpty()) {
                        "" -> null
                        FaultTemplate.blank.id -> FaultTemplate.blank
                        else -> readFaultTemplates(context, session.draftScope).firstOrNull { it.id == templateId }
                    },
                )
            }
            if (batch) form() else OperationAccountScope("Fault:$id", form)
        }
        composable(ERP_FORM, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { entry ->
            OperationAccountScope("Erp:" + entry.arguments?.getString("id").orEmpty()) {
            ErpFormScreen(existingId = entry.arguments?.getString("id").orEmpty().ifEmpty { null }, referenceSn = null, onClose = { nav.popBackStack() }, onSaved = {})
                    }
        }
        composable(REPAIR, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            OperationAccountScope("Fault:" + entry.arguments?.getString("id").orEmpty()) {
            val id = entry.arguments?.getString("id").orEmpty()
            RepairFormScreen(
                faultId = id, onClose = { nav.popBackStack() },
                // The result is saved: on to the step itself (choose the approver, write the opinion, send). It replaces this page.
                onSubmitted = { nav.navigate("task/Fault/" + Uri.encode(id) + "?ret=false") { popUpTo(REPAIR) { inclusive = true } } },
            )
                    }
        }

        composable("quick-finish") {
            OperationAccountScope(quick.operationKey) { QuickFinishScreen(quick, onBack = {
                if (nav.currentDestination?.route == "quick-finish") nav.popBackStack()
            }) }
        }
        composable("management") {
            PersonnelManagementScreen(onBack = { nav.popBackStack() }, openOrder = { id -> nav.navigate("fault/" + Uri.encode(id) + "?independent=true") })
        }
        composable("maintenance-statistics") {
            MaintenanceStatisticsScreen(onBack = { nav.popBackStack() }, openOrder = { id -> nav.navigate("pool-order/maintain/" + Uri.encode(id)) })
        }
        composable("query-account-settings") { QueryAccountSettingsScreen { nav.popBackStack() } }
        composable("precision-dashboard") { PrecisionDashboard(onBack = { nav.popBackStack() }, openAsset = { nav.navigate("precision-asset/" + Uri.encode(it)) }) }
        composable("precision-asset/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            PrecisionDeviceScreen(entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
        composable(DEV) { entry ->
            DevScreen(onBack = {
            if (nav.currentBackStackEntry == entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) nav.popBackStack()
        }) }
        composable(DRAFTS) {
            DraftBoxScreen(onBack = { nav.popBackStack() },
                onOpenNew = { draft -> nav.navigate(newFaultRoute(draft = draft)) },
                onOpenBatch = { draft -> nav.navigate(newFaultRoute(draft = draft, batch = true)) })
        }
        composable(COMPLETED_ORDERS) { entry ->
            if (AppPreferences.todoCompletionEnabled) {
                CompletedOrdersScreen(
                    onBack = { nav.popBackStack() },
                    openWorkflow = { processId -> nav.navigate("flow/" + Uri.encode(processId)) },
                )
            } else {
                androidx.compose.runtime.LaunchedEffect(entry) {
                    if (nav.currentBackStackEntry == entry) nav.popBackStack()
                }
            }
        }
        composable("pool-order/{kind}/{id}", arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })) { entry ->
            val kind = PoolKind.byPath(entry.arguments?.getString("kind").orEmpty()) ?: PoolKind.Maintenance
            PoolOrderDetail(kind, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
        composable(POOL, arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("state") { type = NavType.StringType; defaultValue = "RECEIVED" })) { entry ->
            val kind = PoolKind.byPath(entry.arguments?.getString("kind").orEmpty()) ?: PoolKind.Maintenance
            var selectedState by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(entry.arguments?.getString("state").orEmpty()) }
            val vm = viewModel<PagedViewModel>(
                key = "pool-" + kind.path,
                factory = viewModelFactory {
                    initializer { PagedViewModel(session, kind.page, kind.stateFilters(selectedState), kind.searches()) }
                },
            )
            PagedScreen(
            vm, if (kind == PoolKind.Maintenance) "保养工单" else kind.title, "暂无相关工单", modelOf = { row, index -> poolCardModel(kind, row, index) }, onBack = { nav.popBackStack() },
            open = { model, _ ->
                if (model.openId.isNotBlank()) nav.navigate("pool-order/${kind.path}/" + Uri.encode(model.openId))
            }, poolKind = kind, cardType = if (kind == PoolKind.Maintenance) CardType.Maintenance else CardType.Precision,
            reportSelected = ::openSelectedReports,
            extraFilters = {
                val states = kind.states()
                SingleChoiceGroup(states.map { it.second }, states.indexOfFirst { it.first == selectedState }, {
                    selectedState = states[it].first
                    vm.changeFilters(kind.stateFilters(selectedState))
                }, scrollable = true)
            },
        )
        }
        composable(PAGED, arguments = listOf(navArgument("type") { type = NavType.StringType })) { entry ->
            val list = WorkList.byKey(entry.arguments?.getString("type").orEmpty()) ?: WorkList.Assets
            val vm = viewModel<PagedViewModel>(
                key = "paged-" + list.key,
                factory = viewModelFactory { initializer { PagedViewModel(session, list.page, list.base(), list.searches(), independent = true) } },
            )
            PagedScreen(
                vm, list.title, "没有找到相关内容",
                cardType = when (list) { WorkList.Assets -> CardType.Asset; WorkList.Outbound -> CardType.Erp; WorkList.Stock -> CardType.Stock },
                modelOf = { row, index -> when (list) { WorkList.Assets -> assetCardModel(row, index); WorkList.Outbound -> erpCardModel(row); WorkList.Stock -> stockCardModel(row, index) } },
                onBack = { nav.popBackStack() },
                open = if (list == WorkList.Outbound) ({ model, row -> OrderPreview.put("Erp", model.openId, row); nav.navigate("erp/" + Uri.encode(model.openId) + "?independent=true") }) else null,
                onNew = if (list == WorkList.Outbound) ({ nav.navigate("form/erp?id=") }) else null,
                batchKind = if (list == WorkList.Outbound) OrderKind.Erp else null,
                reportAsset = if(list == WorkList.Assets) ::reportAsset else null,
            )
        }
        composable(FLOW, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            WorkflowScreen(processId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
    OperationAccountScope(quick.operationKey) {
        QuickFinishDialogs(quick, openForm = { nav.navigate("quick-finish") { launchSingleTop = true } },
            openOrder = { item -> nav.navigate(if (item.poolPrefix.isEmpty()) "fault/" + Uri.encode(item.id) else "pool-order/${item.poolPrefix}/" + Uri.encode(item.id)) })
    }
    OperationAccountDialog()
    }
}
