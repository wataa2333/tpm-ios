package com.personal.tpm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.DraftTarget

/**
 * The draft box: the repair reports that were saved on this phone for this account and factory. A draft of a new report opens in the report form;
 * the draft of an existing order is continued from that order ("编辑"), because it belongs to it alone.
 */
@Composable
fun DraftBoxScreen(onBack: () -> Unit, onOpenNew: (String) -> Unit) {
    val session = LocalSession.current
    var notice by remember { mutableStateOf<String?>(null) }
    GlassScaffold(contentWindowInsets = appContentInsets(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "草稿箱", onBack = onBack) },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            RoundedSheet {
                PageWidth {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
                        DraftList(session.draftScope, DraftTarget.Everything) { draft ->
                            if (draft.orderId == null) onOpenNew(draft.id)
                            else notice = "这份草稿属于单号 " + (draft.orderSn ?: "") + " 的工单。请打开那张工单，点右上角的“编辑”继续。"
                        }
                        listOf("维修完成" to session.repairDraftScope,
                            "保养完成" to (session.repairDraftScope + ":pool:maintain"),
                            "精度完成" to (session.repairDraftScope + ":pool:precise")).forEach { (title, draftScope) ->
                            FormTitle("$title 草稿")
                            DraftList(draftScope, DraftTarget.Everything) {
                                notice = "请从“我的待办 → 快速完成”重新核对数量并继续，对应单据会恢复保存的填写内容。"
                            }
                        }
                    }
                }
            }
        }
    }
    notice?.let { ErrorDialog("请从工单里继续", it) { notice = null } }
}
