package com.personal.tpm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.PickerEntry
import com.personal.tpm.data.PickerLists
import com.personal.tpm.data.PickerRules
import com.personal.tpm.data.PickerSearch
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.Session
import com.personal.tpm.data.filter
import com.personal.tpm.data.materialQuantity
import com.personal.tpm.data.quantityText
import com.personal.tpm.data.referenceKey
import com.personal.tpm.data.resolveReference
import com.personal.tpm.data.searchModes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One row of a tree (departments, fault types) laid flat: how deep it is, whether it opens, whether it can be chosen. */
private class TreeRow(val path: String, val node: Jv, val depth: Int, val opens: Boolean, val choosable: Boolean)

private fun flatten(nodes: List<Jv>, source: RefSource, expanded: Set<String>, openAll: Boolean, depth: Int = 0, parent: String = ""): List<TreeRow> = buildList {
    nodes.forEachIndexed { index, node ->
        val path = "$parent/${node.label}#$index"
        val children = node["children"].array
        // A department or a fault type is chosen at its end of the branch only; an ERP division can be chosen at any level.
        val choosable = !node["disabled"].bool && when (source) {
            RefSource.FaultTypes -> children.isEmpty() && node["type"].text == "FaultType"
            RefSource.Divisions -> true
            else -> children.isEmpty() && node["value"].text != "root"
        }
        if (children.isNotEmpty() || choosable) add(TreeRow(path, node, depth, children.isNotEmpty(), choosable))
        if (children.isNotEmpty() && (openAll || path in expanded)) addAll(flatten(children, source, expanded, openAll, depth + 1, path))
    }
}

/**
 * The divisions as a tree: when the server nests them (`children`) as they come; when it sends a flat list in which a row names its parent, the
 * rows are hung under their parents; a list with no such link stays flat.
 */
private fun divisionTree(values: List<Jv>): List<Jv> {
    if (values.any { it["children"].array.isNotEmpty() }) return values
    fun idOf(v: Jv) = v["divisionId"].text.ifEmpty { v["id"].text }
    val ids = values.map(::idOf).filter { it.isNotEmpty() }.toSet()
    val parentKey = listOf("parentId", "parentDivisionId", "pid", "parentDivisionCode").firstOrNull { key -> values.any { it[key].text.isNotEmpty() && it[key].text in ids } } ?: return values
    fun build(parentId: String?, seen: Set<String>): List<Jv> = values.filter { v ->
        val parent = v[parentKey].text
        if (parentId == null) parent.isEmpty() || parent !in ids else parent == parentId
    }.filter { idOf(it) !in seen }.map { v ->
        val kids = build(idOf(v), seen + idOf(v))
        if (kids.isEmpty()) v else Jv(JSONObject(v.raw.toString()).put("children", org.json.JSONArray(kids.map { it.raw })))
    }
    return build(null, emptySet())
}

/** The tree with only the nodes whose name matches, and the parents that lead to them. */
private fun matching(nodes: List<Jv>, query: String): List<Jv> = nodes.mapNotNull { node ->
    if (node.label.contains(query, ignoreCase = true)) return@mapNotNull node
    val children = matching(node["children"].array, query)
    if (children.isEmpty()) null else Jv(JSONObject(node.raw.toString()).put("children", org.json.JSONArray(children.map { it.raw })))
}

private suspend fun loadWhole(session: Session, source: RefSource, query: String): List<Jv> = when (source) {
    RefSource.Users -> session.request("security/getSelectableUsersByFactory").array.filter { it["enabled"].raw != false }.onEach { UserNames.remember(it) }
    RefSource.WorkflowUsers -> session.request("workflow/searchTransferUsers", mapOf("keyword" to query, "size" to "20")).array
    RefSource.Groups -> session.request("user/getEnabledUserGroupsByGroupType", mapOf("groupType" to "REPAIR")).array
    RefSource.Departments -> session.request("dept/getDeptSimpleTree").array
    RefSource.Divisions -> session.request("erp/getAllDivisionByFactoryId").array
    RefSource.Projects -> session.request("erpOutbound/getProjectByCompanyCode").array
    RefSource.FaultTypes -> session.request("faultType/getFaultTypeTree", mapOf("enabled" to "true")).array
    else -> emptyList()
}

private fun available(line: Jv) = line["actualQuantity"].number - line["usedQuantity"].number

/**
 * A full-height sheet to choose from a list: people (by name or by group), repair groups, assets, departments and fault types (trees),
 * ERP divisions, projects, stock materials and already-issued material lines (the last three searched on the server, several can be ticked).
 * Long-press a row for "置顶" (up to ten, listed first) and, where the field has one, "设为默认"; the last three chosen are listed under
 * "最近使用". With [multiple] rows are ticked and the button finishes. Pins, recents and defaults are kept on this phone for this account
 * and factory. A department, a person and a fault type are read once more for their full record when chosen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReferenceSheet(
    source: RefSource, selected: Set<String>, multiple: Boolean, defaultField: String?, onToggle: (Jv) -> Unit, onDismiss: () -> Unit, onRemove: ((Jv) -> Unit)? = null,
) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val scope = rememberCoroutineScope()
    val scopeKey = session.draftScope
    val modes = searchModes(source)
    val paged = source.pagePath != null
    var values by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var userGroups by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(true) }
    var selecting by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var committed by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(PickerSearch.Name) }
    var userMode by remember { mutableStateOf(0) }
    var lists by remember { mutableStateOf(if (source.hasMenu) prefs.lists(scopeKey, source) else PickerLists()) }
    var defaultKey by remember { mutableStateOf(defaultField?.let { prefs.defaultChoice(scopeKey, it)?.key }) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(selected) }
    var hideUsedUp by remember { mutableStateOf(true) }
    val opened = remember { mutableStateListOf<String>() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    suspend fun load(more: Boolean) {
        if (more) loadingMore = true else loading = true
        error = null
        try {
            val path = source.pagePath
            if (path != null) {
                val filters = buildList {
                    if (source == RefSource.Materials) add(Jv.obj("field" to "onhandQty", "operator" to "gt", "value" to 0, "logic" to "and"))
                    if (committed.isNotEmpty()) add(filter(searchMode.field(source), committed, "like"))
                }
                // Sort on the server so the date order holds across pages, including searches.
                val answer = if (source == RefSource.OutboundMaterials) {
                    session.request(path, body = Jv.obj(
                        "offset" to if (more) values.size else 0, "limit" to 20,
                        "sort" to "outbound.applyDate", "order" to "desc",
                        "filters" to org.json.JSONArray(filters),
                    ))
                } else session.page(path, if (more) values.size else 0, filters)
                values = if (more) values + answer["content"].array else answer["content"].array
                last = answer["last"].bool
            } else values = loadWhole(session, source, committed)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败" }
        finally { loading = false; loadingMore = false }
    }
    LaunchedEffect(source, searchMode) { load(false) }
    LaunchedEffect(userMode) {
        if (source == RefSource.Users && userMode == 1 && userGroups.isEmpty()) {
            try { userGroups = session.request("user/getUserGroupsByFactoryId").array }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "加载失败" }
        }
    }

    fun entryOf(value: Jv) = PickerEntry(referenceKey(source, value), if (source == RefSource.OutboundMaterials) value["material"].label else value.label, value)
    suspend fun dismiss(afterHidden: () -> Unit = {}) {
        if (closing) return
        closing = true
        try {
            sheetState.hide()
            afterHidden()
            onDismiss()
        } finally { closing = false }
    }
    fun finish() { scope.launch { dismiss() } }
    fun choose(value: Jv) {
        if (selecting || closing) return
        val key = entryOf(value).key
        if (multiple && key in picked) {
            (onRemove ?: onToggle)(value)
            picked = picked - key
            return
        }
        if (source == RefSource.OutboundMaterials) {
            if (available(value) < 1) { notice = "这条出库物料的可用数量小于 1，不能选择。"; return }
        }
        selecting = true
        scope.launch {
            try {
                val result = resolveReference(session, source, value)
                if (closing) return@launch
                if (!multiple && source.hasMenu) lists = PickerRules.record(entryOf(value), lists).also { prefs.save(scopeKey, source, it) }
                UserNames.remember(value)
                if (multiple) {
                    onToggle(result)
                    picked = picked + key
                } else {
                    picked = setOf(key)
                    // A selection callback may remove the host; run it only after the native sheet exits.
                    dismiss { onToggle(result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "选择失败" }
            finally { selecting = false }
        }
    }

    val searching = query.isNotBlank()
    val treeSource = source == RefSource.Departments || source == RefSource.FaultTypes || source == RefSource.Divisions
    val treeValues = if (source == RefSource.Divisions) divisionTree(values) else values
    val shown = when {
        source == RefSource.OutboundMaterials -> values.filter { !hideUsedUp || available(it) > 0 }
        paged || source == RefSource.WorkflowUsers || !searching || treeSource -> values
        else -> values.filter { (it.label + it["userName"].text + it["groupName"].text + it["projectCode"].text).contains(query.trim(), ignoreCase = true) }
    }
    val tree = if (treeSource) flatten(if (searching) matching(treeValues, query.trim()) else treeValues, source, opened.toSet(), searching) else emptyList()
    val hasBranches = treeSource && flatten(treeValues, source, emptySet(), true).any { it.opens }

    fun subtitleLines(v: Jv): List<String> = when (source) {
        RefSource.Materials -> listOf(listOf(v["materialCode"].text, "库存 " + materialQuantity(v["onhandQty"].text, v), v["lot"].text, v["subInv"].text, v["locatorCode"].text).filter { it.isNotEmpty() }.joinToString(" · "))
        RefSource.OutboundMaterials -> {
            val outbound = v["outbound"]
            val people = listOf("申请人" to outbound["user"]["realName"].text, "领用人" to outbound["recipientUser"]["realName"].text).filter { it.second.isNotEmpty() }.joinToString("　") { it.first + "：" + it.second }
            val left = available(v)
            listOf(
                listOf(outbound["outNumber"].text, v["material"]["materialCode"].text).filter { it.isNotEmpty() }.joinToString(" · "), people,
                "可用 " + materialQuantity(quantityText(left), v["material"]) + (if (left < 1) "（不足 1，不能选择）" else "") + (if (outbound["applyDate"].text.isEmpty()) "" else " · 申请日期 " + outbound["applyDate"].dateText),
            ).filter { it.isNotEmpty() }
        }
        else -> listOf(listOf(v["assetCode"].text, v["addressCode"].text, v["userName"].text, v["projectCode"].text, v["divisionName"].text).filter { it.isNotEmpty() }.joinToString(" · ")).filter { it.isNotEmpty() }
    }

    @Composable
    fun PickerRow(entry: PickerEntry, depth: Int = 0) {
        val key = entry.key
        val lines = subtitleLines(entry.value)
        // Each choice is a block of its own on the white sheet (a tinted one when it is ticked), so the rows never run together.
        Box(Modifier.fillMaxWidth().padding(start = (20 + depth.coerceAtMost(4) * 16).dp, end = 12.dp, top = 3.dp, bottom = 3.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (key in picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else if (treeSource) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(onClick = { choose(entry.value) }, onLongClick = { if (source.hasMenu) menuFor = key })
                    .heightIn(min = 44.dp).padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedLabel(entry.label, style = MaterialTheme.typography.bodyMedium)
                    lines.forEach { AnimatedLabel(it, style = MaterialTheme.typography.bodySmall, color = if (source == RefSource.OutboundMaterials && available(entry.value) < 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (lists.pins.any { it.key == key }) Icon(Icons.Filled.PushPin, contentDescription = "已置顶", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                if (defaultKey == key) Text("默认", style = MaterialTheme.typography.labelMedium, color = Color(0xFFF57C00))
                if (key in picked) Icon(Icons.Filled.Check, contentDescription = "已选", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            // Long-press actions use the shared bottom option sheet.
            AppOptionSheet(
                expanded = menuFor == key, onDismissRequest = { menuFor = null },

            ) {
                val pinned = lists.pins.any { it.key == key }
                AppOptionItem(
                    text = { AnimatedLabel(if (pinned) "取消置顶" else "置顶") }, leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = {
                        menuFor = null
                        val next = if (pinned) PickerRules.unpin(key, lists) else PickerRules.pin(entry, lists)
                        if (next == null) notice = "最多只能置顶 ${PickerLists.PIN_LIMIT} 项，请先取消一个置顶。"
                        else { lists = next; prefs.save(scopeKey, source, next) }
                    },
                )
                if (defaultField != null) AppOptionItem(
                    text = { AnimatedLabel(if (defaultKey == key) "取消默认" else "设为默认") }, leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    onClick = {
                        menuFor = null
                        val on = defaultKey != key
                        prefs.setDefault(scopeKey, defaultField, if (on) entry else null)
                        defaultKey = if (on) key else null
                    },
                )
            }
        }
    }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) { CompactTheme {
        // Not the whole screen: a strip of the page stays above the sheet, so it never reaches into the status bar and swiping down is clear.
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedLabel(source.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                GlassTextButton(onClick = ::finish, enabled = !closing) { AnimatedLabel(if (multiple) "完成（${picked.size}）" else "关闭") }
            }
            SearchField(
                query, { query = it }, if (modes.isEmpty()) "搜索" else "搜索" + searchMode.label(source),
                onSearch = { if (paged || source == RefSource.WorkflowUsers) { committed = query.trim(); scope.launch { load(false) } } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // The ways of searching: a row of chips (it scrolls sideways if the screen is narrow), each on one line.
            if (modes.isNotEmpty()) SingleChoiceGroup(modes.map { it.label(source) }, modes.indexOf(searchMode), { searchMode = modes[it] },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), scrollable = modes.size > 3)
            if (source == RefSource.Users) SingleChoiceGroup(listOf("按姓名", "按班组"), userMode, { userMode = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { if (loading || selecting) AppProgressIndicator(Modifier.fillMaxWidth()) }
            if (source.hasMenu) Text(
                if (defaultField != null) "长按某一项，可以置顶，或设为默认。" else "长按某一项，可以置顶。",
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedLabel(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    GlassTextButton(onClick = { scope.launch { load(false) } }) { Text("重试") }
                }
            }
            // Open or close every branch at once.
            if (hasBranches && !searching) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextButton(onClick = { flatten(treeValues, source, emptySet(), true).filter { it.opens }.forEach { if (it.path !in opened) opened.add(it.path) } }) { Text("全部展开") }
                GlassTextButton(onClick = { opened.clear() }) { Text("全部收起") }
            }
            if (source == RefSource.OutboundMaterials) Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("隐藏已领用完物料", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = hideUsedUp, onCheckedChange = { hideUsedUp = it })
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 12.dp)) {
                if (source.hasMenu && !searching && lists.pins.isNotEmpty()) {
                    item(key = "pins-title") { SectionLabel("置顶 · ${lists.pins.size}/${PickerLists.PIN_LIMIT}") }
                    items(lists.pins, key = { "pin-" + it.key }) { Box(fadeItem()) { PickerRow(it) } }
                }
                val recents = if (!source.hasMenu || multiple || searching) emptyList() else PickerRules.shownRecents(lists)
                if (recents.isNotEmpty()) {
                    item(key = "recents-title") { SectionLabel("最近使用") }
                    items(recents, key = { "recent-" + it.key }) { Box(fadeItem()) { PickerRow(it) } }
                }
                if (source.hasMenu && !searching && (lists.pins.isNotEmpty() || recents.isNotEmpty())) item(key = "all-title") { SectionLabel("全部") }
                when {
                    treeSource -> items(tree.size, key = { "tree-" + tree[it].path }, contentType = { if (tree[it].opens) "branch" else "leaf" }) { index ->
                        val row = tree[index]
                        // Stable paths let following branches move continuously on both expand and collapse.
                        Box(Modifier.animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(Motion.Slow, easing = Motion.Arrive),
                            placementSpec = androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change),
                            fadeOutSpec = androidx.compose.animation.core.tween(Motion.Instant, easing = Motion.Leave),
                        )) {
                        if (row.opens) {
                            val open = searching || row.path in opened
                            val toggle = { if (row.path in opened) opened.remove(row.path) else opened.add(row.path); Unit }
                            // A branch: a block with an arrow that opens and closes it. Where a branch can be chosen itself (a division), the name chooses it and only
                            // the arrow opens; otherwise the whole block opens it.
                            Box(Modifier.fillMaxWidth().padding(start = (20 + row.depth.coerceAtMost(4) * 16).dp, end = 12.dp, top = 3.dp, bottom = 3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .then(if (row.choosable) Modifier else Modifier.clickable(onClick = toggle)),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        row.node.label,
                                        Modifier.weight(1f).then(if (row.choosable) Modifier.clickable { choose(row.node) } else Modifier).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    )
                                    Box(Modifier.then(if (row.choosable) Modifier.clickable(onClick = toggle) else Modifier).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), label = "branchArrow")
                                        Icon(Icons.Filled.ExpandMore, contentDescription = if (open) "收起" else "展开", Modifier.size(22.dp).graphicsLayer { rotationZ = turn })
                                    }
                                }
                            }
                        } else PickerRow(entryOf(row.node), row.depth)
                        }
                    }
                    source == RefSource.Users && userMode == 1 -> {
                        userGroups.forEachIndexed { gi, group ->
                            val users = group["users"].array.filter { it["enabled"].raw != false && (!searching || group["groupName"].text.contains(query.trim(), true) || (it["realName"].text + it["userName"].text).contains(query.trim(), true)) }
                            if (users.isNotEmpty()) {
                                item(key = "group-$gi") { SectionLabel(group["groupName"].text) }
                                items(users.size, key = { "group-$gi-${users[it]["userName"].text}-$it" }) { Box(fadeItem()) { PickerRow(entryOf(users[it])) } }
                            }
                        }
                    }
                    else -> items(shown.size, key = { "all-" + referenceKey(source, shown[it]) + it }) { Box(fadeItem()) { PickerRow(entryOf(shown[it])) } }
                }
                if (paged && values.isNotEmpty() && !last) item(key = "more") {
                    LaunchedEffect(values.size) { if (!loading && !loadingMore) load(true) }
                    LoadMoreFooter(loadingMore)
                }
                if (!loading && error == null && (if (treeSource) tree.isEmpty() else if (source == RefSource.Users && userMode == 1) userGroups.isEmpty() else shown.isEmpty())) item(key = "empty") { EmptyState("没有匹配的内容") }
            }
            if (multiple) GlassBottomSurface() {
                GlassActionButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { AnimatedLabel(if (picked.isEmpty()) "完成" else "完成（已选 ${picked.size} 项）") }
            }
            notice?.let {
                Snackbar(Modifier.padding(16.dp), action = { GlassTextButton(onClick = { notice = null }) { Text("好") } }) { AnimatedLabel(it) }
            }
        }
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    AnimatedLabel(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
