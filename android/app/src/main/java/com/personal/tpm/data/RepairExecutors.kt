package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Match the original RepairInfo: getRepairsByFaultId's first record user, even before sign-in.
 * The fault's receiver and repairUsers helpers are never executor fallbacks. */
fun repairExecutorDocument(document: ManagedDocument, detail: Jv, repair: Jv?): ManagedDocument? {
    require(detail["faultId"].text == document.id) { "详情编号不符" }
    val state = if (document.phase == ManagedPhase.Sign) "SIGNED" else "REPAIRED"
    require(detail["faultState"].text == state) { "详情状态已变化，请重新查询" }
    val direct = managedDocument(ManagedKind.Fault, detail, state, null)!!
    if (repair == null) return if(direct.personKey == null) null else document.copy(personKey=direct.personKey,personName=direct.personName)
    val owner = repair["assetFault"]
    require(owner["faultId"].text.isBlank() || owner["faultId"].text == document.id) { "维修记录关联单据不符" }
    val task = detail["taskInfo"]["id"].text
    val repairTask = owner["taskInfo"]["id"].text
    require(task.isBlank() || repairTask.isBlank() || task == repairTask) { "维修记录任务已变化" }
    val identity = Jv(Jv.obj("faultId" to document.id, "faultState" to state, "repairUser" to repair["user"].raw))
    val parsed = managedDocument(ManagedKind.Fault, identity, state, null)!!
    return when {
        parsed.personKey != null -> document.copy(personKey=parsed.personKey,personName=parsed.personName)
        direct.personKey != null -> document.copy(personKey=direct.personKey,personName=direct.personName)
        else -> null
    }
}

suspend fun resolveRepairExecutors(
    initial: RepairPendingSnapshot,
    retryFailures: Boolean = false,
    concurrency: Int = 3,
    checkScope: () -> Unit = {},
    progress: (RepairPendingSnapshot) -> Unit = {},
    detail: suspend (String) -> Jv,
    repairInfo: suspend (String) -> Jv,
): RepairPendingSnapshot = coroutineScope {
    require(concurrency in 1..8)
    var snapshot = initial
    val targets = initial.documents.filter { it.key !in initial.executorChecks || (retryFailures && initial.executorChecks[it.key]?.startsWith("失败") == true) }
    for (chunk in targets.chunked(concurrency)) {
        checkScope()
        val results = chunk.map { document -> async {
            checkScope()
            try {
                val order = detail(document.id)
                checkScope()
                // Validate current state before making any second request.
                repairExecutorDocument(document, order, null)
                val repair = repairInfo(document.id); checkScope()
                val resolved = repairExecutorDocument(document, order, repair)
                Triple(document, resolved, if (resolved == null) "未提供明确执行人" else "已核对")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Triple(document, null, "失败：${e.message ?: "读取异常"}") }
        } }.awaitAll()
        checkScope()
        for ((original, resolved, status) in results) {
            val changed = status.contains("状态已变化")
            snapshot = snapshot.copy(
                documents = snapshot.documents.mapNotNull { if (it.key != original.key) it else if (changed) null else resolved ?: it.copy(personKey = null, personName = "执行人未明确") },
                executorChecks = snapshot.executorChecks + (original.key to if (changed) "状态已变化，请重新查询" else status),
                pages = if (changed) snapshot.pages.map { it.copy(warnings = it.warnings + "详情核对发现状态变化，请重新查询") } else snapshot.pages,
            )
        }
        progress(snapshot)
    }
    snapshot
}
