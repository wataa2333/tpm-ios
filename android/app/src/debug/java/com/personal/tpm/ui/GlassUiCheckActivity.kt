package com.personal.tpm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personal.tpm.ui.theme.AppTheme

/** Debug-only, local fixtures: no sign-in, API reads, workflow writes or account data. */
class GlassUiCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPreferences.updateLiquidGlass(true)
        setContent {
            AppTheme {
                val colors = MaterialTheme.colorScheme.copy(primary = Color(0xFFE2A400), onPrimary = Color.White,
                    primaryContainer = Color(0xFFFFEAB6), onPrimaryContainer = Color(0xFF302200))
                MaterialTheme(colorScheme = colors) { CheckContent() }
            }
        }
    }
}

@Composable private fun CheckContent() {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val tabs = listOf(GlassTab("主页", Icons.Default.Home), GlassTab("我的待办", Icons.Default.Checklist),
        GlassTab("工作台", Icons.Default.Dashboard), GlassTab("我的", Icons.Default.Person))
    GlassMainFrame(
        topBar = { AppTopBar("玻璃界面检查", actions = {
            GlassIconButton(onClick = { selecting = !selecting }) { Icon(Icons.Default.Checklist, "切换多选") }
        }) },
        bottomBar = { GlassBottomBar(tabs, tab, 2) { tab = it } },
        floatingActionButton = {
            GlassActionButton(onClick = {}, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新建报修")
            }
        }, snackbarHost = {},
    ) {
        ScrollableLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("本地样例 · 第 ${tab + 1} 页", style = MaterialTheme.typography.titleSmall) }
            items(5) { index ->
                val model = previewCard(CardType.Fault).copy(key = "$index", title = "示例维修 $index · 主轴运行异常")
                OrderCard(model, expanded[index] == true, { expanded[index] = expanded[index] != true },
                    selecting = if (selecting) CardSelecting(selected, true, { selected = !selected }, {}) else null)
            }
            item {
                GroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("人员统计进度示例")
                    StatisticsProgressIndicator(progress = { .5f })
                    Text("列表已读 74 / 74 · 详情已核对 0 / 74")
                } }
            }
        }
    }
}
