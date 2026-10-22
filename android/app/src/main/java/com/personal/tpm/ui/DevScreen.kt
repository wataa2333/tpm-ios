package com.personal.tpm.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.personal.tpm.MainActivity
import com.personal.tpm.ui.theme.ThemeConfig
import kotlin.math.roundToInt

/**
 * The developer page (opened from 我的 → 其他 after password verification): the motion scale, the state of the predictive back gesture,
 * what the screen and the frame rate are, the numbers of the interface size, and the older loading animations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var unlocked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    if (!unlocked) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBack, title = { Text("开发者验证") },
            text = { androidx.compose.material3.OutlinedTextField(value = password, onValueChange = { password = it.take(16); wrong = false },
                label = { Text(if (wrong) "密码不正确" else "请输入密码") }, isError = wrong,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword), singleLine = true) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { if (password == "1331") { unlocked = true; password = "" } else wrong = true }) { Text("验证") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onBack) { Text("取消") } },
        )
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "开发者", onBack = onBack) },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            RoundedSheet {
                PageWidth {
                    Column(
                        Modifier.elastic().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle("管理功能")
                        GroupCard {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("管理看板", Modifier.weight(1f))
                                Switch(checked = AppPreferences.management, onCheckedChange = AppPreferences::updateManagement)
                            }
                        }
                        SectionTitle("精密度接口验证")
                        PrecisionApiProbe()
                        // ---- motion ----
                        SectionTitle("维修查询实验")
                        GroupCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("自定义读取档位", Modifier.weight(1f))
                                    Switch(AppPreferences.repairReadExperiment, AppPreferences::updateRepairReadExperiment)
                                }
                                Reveal(AppPreferences.repairReadExperiment) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("每页工单数量", style = MaterialTheme.typography.titleSmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AppPreferences.repairPageSizes.forEach { size -> ChoiceChip(AppPreferences.repairPageSize == size, { AppPreferences.updateRepairPageSize(size) }, "$size 单") }
                                        }
                                        Text("详情核对并发", style = MaterialTheme.typography.titleSmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AppPreferences.repairConcurrencyLevels.forEach { count -> ChoiceChip(AppPreferences.repairConcurrency == count, { AppPreferences.updateRepairConcurrency(count) }, "$count 单") }
                                        }
                                    }
                                }
                                Text("当前：每页${AppPreferences.effectiveRepairPageSize}单，详情同时核对${AppPreferences.effectiveRepairConcurrency}单。", style = MaterialTheme.typography.bodySmall)
                                Text("下一次重新查询生效，暂停后继续沿用原档位。处理统计只读列表，并发档位仅用于待处理详情。关闭开关恢复默认500／8。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SectionTitle("动画时长与曲线")
                        GroupCard {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Motion.tiers.indices.forEach { index ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(Motion.tierTitles[index], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text("${Motion.length(index)} ms", style = MaterialTheme.typography.bodyMedium, color = accentInk())
                                    }
                                    Slider(
                                        value = Motion.length(index).toFloat(), onValueChange = { Motion.setLength(index, (it / 10f).roundToInt() * 10) },
                                        valueRange = 40f..1000f,
                                    )
                                }
                            }
                        }
                        GroupCard {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Motion.roleTitles.indices.forEach { role ->
                                    Text(Motion.roleTitles[role], style = MaterialTheme.typography.bodyMedium)
                                    BlockSegmented(Motion.Curve.entries.map { it.title }, Motion.curve(role).ordinal, { Motion.setCurve(role, Motion.Curve.entries[it]) })
                                }
                            }
                        }
                        GroupCard {
                            var on by remember { mutableStateOf(false) }
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("试播：用“慢”的时长，三条分别用到来、离开、变化的曲线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                listOf("到来" to Motion.Arrive, "离开" to Motion.Leave, "变化" to Motion.Change).forEach { (name, curve) ->
                                    val fraction by animateFloatAsState(if (on) 1f else 0f, tween(Motion.Slow, easing = curve), label = "demo")
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(name, Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary))
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SoftButton(onClick = { on = !on }, modifier = Modifier.weight(1f)) { Text("试播") }
                                    SoftButton(onClick = { Motion.reset() }, modifier = Modifier.weight(1f), enabled = !Motion.isDefault) { Text("恢复默认") }
                                }
                            }
                        }

                        // ---- predictive back ----
                        SectionTitle("预测性返回")
                        GroupCard {
                            val sdk = Build.VERSION.SDK_INT
                            val switch = if (sdk in 33..34) {
                                when (runCatching { Settings.Global.getInt(context.contentResolver, "enable_back_animation", -1) }.getOrDefault(-1)) {
                                    1 -> "已打开"; 0 -> "未打开（开发者选项里打开“预测性返回动画”）"; else -> "读不到，请到开发者选项里查看"
                                }
                            } else if (sdk >= 35) "Android 15 起系统默认启用" else "不支持（Android 13 以下）"
                            FactRow("安卓版本", Build.VERSION.RELEASE + "（API $sdk）")
                            FactRow("应用声明", "已声明支持")
                            FactRow("系统开关", switch)
                        }

                        // ---- smoothness ----
                        SectionTitle("流畅度与刷新率")
                        GroupCard {
                            val display = runCatching { if (Build.VERSION.SDK_INT >= 30) context.display else @Suppress("DEPRECATION") (context as android.app.Activity).windowManager.defaultDisplay }.getOrNull()
                            val rates = display?.supportedModes?.map { it.refreshRate.roundToInt() }?.distinct()?.sortedDescending()?.joinToString(" / ").orEmpty()
                            val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(1f)
                            val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                            FactRow("当前刷新率", (display?.refreshRate?.roundToInt() ?: 0).toString() + " Hz")
                            FactRow("屏幕支持", rates.ifEmpty { "未知" } + " Hz")
                            FactRow("系统动画速度", if (scale == 0f) "已关闭（动画会直接跳过）" else "${scale}×")
                            FactRow("安装包类型", if (debuggable) "调试包（比正式包慢得多，评估流畅度请用正式包）" else "正式包")
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("请求最高刷新率", style = MaterialTheme.typography.titleSmall)
                                    Text("向系统申请屏幕支持的最高刷新率；手机的省电策略有最终决定权", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = ThemeConfig.highRefresh, onCheckedChange = { ThemeConfig.highRefresh = it; (context as? MainActivity)?.applyRefreshRate() })
                            }
                        }

                        // ---- screen ----
                        SectionTitle("显示与屏幕参数")
                        GroupCard {
                            val config = LocalConfiguration.current
                            val density = LocalDensity.current
                            val system = android.content.res.Resources.getSystem().displayMetrics.density
                            val metrics = context.resources.displayMetrics
                            FactRow("界面缩放（适配 × 档位）", "%.2f".format(density.density / system) + " ×")
                            FactRow("当前密度", "%.2f".format(density.density) + "（${config.densityDpi} dpi）")
                            FactRow("系统原始密度", "%.2f".format(system))
                            FactRow("屏幕（dp）", "${config.screenWidthDp} × ${config.screenHeightDp}，最小宽 ${config.smallestScreenWidthDp}")
                            FactRow("屏幕（像素）", "${metrics.widthPixels} × ${metrics.heightPixels}")
                            FactRow("字体缩放", "%.2f".format(density.fontScale))
                            FactRow("显示区域留白（dp）", "上 ${ThemeConfig.displayTop}  下 ${ThemeConfig.displayBottom}  左 ${ThemeConfig.displayStart}  右 ${ThemeConfig.displayEnd}")
                        }

                        // ---- loading animations ----
                        SectionTitle("加载动画（含旧样式）")
                        GroupCard {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ThemeConfig.LoadingStyle.entries.forEach { style ->
                                        ChoiceChip(ThemeConfig.loadingStyle == style, { ThemeConfig.loadingStyle = style }, style.title + if (style.extra) "（旧）" else "")
                                    }
                                }
                                LoadingStylePreview(ThemeConfig.loadingStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}
