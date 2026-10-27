package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject

/** Normalized display data only; never a raw response, credential, workflow payload or token. */
fun repairSnapshotCache(snapshot: RepairPendingSnapshot): JSONObject = Jv.obj(
    "pageSize" to snapshot.pageSize,
    "pages" to JSONArray(snapshot.pages.map { page -> Jv.obj("state" to page.state,"offset" to page.offset,"expected" to page.expected,"ended" to page.ended,"warnings" to JSONArray(page.warnings.toList())) }),
    "checks" to JSONObject(snapshot.executorChecks),
    "documents" to JSONArray(snapshot.documents.map { doc -> Jv.obj("kind" to doc.kind.name,"id" to doc.id,"title" to doc.title,"number" to doc.number,"phase" to doc.phase.name,"personKey" to doc.personKey,"personName" to doc.personName,"asset" to doc.asset,"end" to doc.end,"state" to doc.state) }),
)

fun repairSnapshotFromCache(value: Jv, processed: Boolean): RepairPendingSnapshot? = runCatching {
    require(value.isObject)
    require(value["documents"].raw is JSONArray && value["checks"].raw is JSONObject)
    val allowed=if(processed) acceptedRepairStates else listOf("SIGNED","REPAIRED")
    val pages=value["pages"].array.map { page ->
        RepairPendingPage(page["state"].text,page["offset"].number.toInt(),if(page["expected"].isNull) null else page["expected"].number.toInt(),page["ended"].bool,page["warnings"].array.map { it.text }.toSet())
    }
    require(pages.map { it.state }==allowed && pages.all { it.offset>=0 && (it.expected==null || it.expected>=0) })
    val documents=value["documents"].array.map { doc ->
        val kind=ManagedKind.valueOf(doc["kind"].text)
        val phase=ManagedPhase.valueOf(doc["phase"].text)
        require(kind==ManagedKind.Fault && doc["id"].text.isNotBlank() && !doc["id"].text.startsWith("excel:"))
        require(if(processed) phase==ManagedPhase.Processed else phase in setOf(ManagedPhase.Sign,ManagedPhase.Finish))
        ManagedDocument(kind,doc["id"].text,doc["title"].text,doc["number"].text,phase,doc["personKey"].text.ifEmpty { null },doc["personName"].text,doc["asset"].text,doc["end"].text,doc["state"].text)
    }
    val checks=value["checks"].keys.associateWith { value["checks"][it].text }
    val pageSize=value["pageSize"].number.toInt()
    require(pageSize in 1..1000 && documents.map { it.key }.distinct().size==documents.size)
    RepairPendingSnapshot(pages,documents,checks,pageSize)
}.getOrNull()
