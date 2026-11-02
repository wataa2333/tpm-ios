package com.personal.tpm.ui

import androidx.navigation.compose.composable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun PersonnelManagementScreen(onBack: () -> Unit, openOrder: (String) -> Unit) {
    val session = LocalSession.current
    val pending = viewModel<RepairPendingViewModel>(key = "repair-pending", factory = viewModelFactory { initializer { RepairPendingViewModel(session) } })
    val history = viewModel<RepairPendingViewModel>(key = "repair-processed", factory = viewModelFactory { initializer { RepairPendingViewModel(session, processed = true) } })
    fun leaveDashboard() { pending.leaveDashboard(); history.leaveDashboard(); onBack() }
    val scope by session.scope.collectAsState()
    var tab by rememberSaveable(scope) { mutableIntStateOf(0) }
    var explanation by rememberSaveable { mutableStateOf(false) }
    val nav = androidx.navigation.compose.rememberNavController()
    androidx.navigation.compose.NavHost(
        navController=nav,startDestination="overview",
        enterTransition={androidx.compose.animation.slideInHorizontally(tween(Motion.Page,easing=Motion.Arrive)){it}},
        exitTransition={androidx.compose.animation.slideOutHorizontally(tween(Motion.Page,easing=Motion.Leave)){-it/4}},
        popEnterTransition={androidx.compose.animation.slideInHorizontally(tween(Motion.Page,easing=Motion.Arrive)){-it/4}},
        popExitTransition={androidx.compose.animation.slideOutHorizontally(tween(Motion.Page,easing=Motion.Leave)){it}}
    ) {
        composable("people") { RepairPersonOrders(pending,onBack={nav.popBackStack()},openOrder=openOrder) }
        composable("trends") { RepairTrendScreen(history,onBack={nav.popBackStack()}) }
        composable("overview") {
    androidx.activity.compose.BackHandler { leaveDashboard() }
    val current = if (tab == 0) pending else history
    val navigationBottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassScaffold(contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),topBar = { AppTopBar("人员工单统计", onBack = ::leaveDashboard, loading = current.loading, linearLoading = true) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=16.dp+navigationBottom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(session.currentFactoryName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                BlockSegmented(listOf("维修待处理", "处理统计"), tab, { tab = it })
            }
            repairPendingSummary(current, openPeople = { nav.navigate("people") { launchSingleTop=true } }, openTrends = { nav.navigate("trends") { launchSingleTop=true } })
            item {
                DisclosureToggle(explanation, if (explanation) "收起统计说明" else "统计说明") { explanation = !explanation }
                Reveal(explanation) { GroupCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ExplanationPart("统计哪些单？","按查询账号、当前厂区的读取权限统计维修单。待处理包括待签到与待完成；处理统计以待确认为门槛，包括待确认、待复线和已完结。工单操作仍使用当前登录账号。")
                    ExplanationPart("日期怎么算？","按故障发现日期筛选，与网页一致；统计图也按故障发现日期汇总，可按天或按月查看。")
                    ExplanationPart("人怎么确定？","待处理逐单核对执行人，处理统计使用列表执行人；无法确认执行人的记录单列。")
                    ExplanationPart("读取到哪了？","点击查询后自动获取数量并读取，可暂停继续。未读完或遇到异常会明确说明；图表不会把未知数量当作0。")
                    Text("数据是分次读取的快照，不代表完整绩效。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } } }
            }
        }
    }
    }
    }
}

@Composable
private fun ExplanationPart(title:String,body:String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
}
