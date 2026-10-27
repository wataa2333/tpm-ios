package com.personal.tpm.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RepairPendingViewModel(private val session: Session, val processed: Boolean = false) : ViewModel() {
    var elapsedMillis by mutableStateOf(0L); private set
    private fun emptySnapshot() = if (processed) RepairPendingSnapshot(pages = acceptedRepairStates.map { RepairPendingPage(it) }) else RepairPendingSnapshot()
    var snapshot by mutableStateOf(emptySnapshot()); private set
    var days by mutableStateOf<Int?>(null); private set
    var customSpan by mutableStateOf<DateSpan?>(null); private set
    var thisMonth by mutableStateOf(false); private set
    val selectedSpan get() = if(thisMonth) currentMonthSpan() else customSpan ?: days?.let { DateSpan(LocalDate.now().minusDays(it.toLong()-1),LocalDate.now()) }
    var ascending by mutableStateOf(false); private set
    var personKey by mutableStateOf<String?>(null); private set
    var estimate by mutableStateOf<RepairReadEstimate?>(null); private set
    private var querySpan: DateSpan? = null
    private var readConcurrency = 3
    val trendSpan get() = querySpan
    var loading by mutableStateOf(false); private set
    var checking by mutableStateOf(false); private set
    var resolving by mutableStateOf(false); private set
    var started by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var queriedAt by mutableStateOf(""); private set
    var previous by mutableStateOf(Jv.NULL); private set
    var showingPrevious by mutableStateOf(false); private set
    val legacyPrevious get() = showingPrevious && !previous["snapshot"].isObject
    private var workloadSnapshot:RepairPendingSnapshot?=null
    private var workloadPeople:List<PersonWorkload> = emptyList()
    val displayPeople: List<PersonWorkload> get() {
        val people=if(legacyPrevious) previous["people"].array.map { PersonWorkload(it["key"].text.ifEmpty { null },it["name"].text,it["sign"].number.toInt(),it["finish"].number.toInt(),it["processed"].number.toInt()) }
            else {
                if(workloadSnapshot !== snapshot) {
                    workloadPeople=personnelWorkloads(if(processed) snapshot.documents else snapshot.documents.filter { it.key in snapshot.executorChecks })
                    workloadSnapshot=snapshot
                }
                workloadPeople
            }
        val comparator=compareBy<PersonWorkload> { if(processed) it.processed else it.pending }
        return people.sortedWith((if(ascending) comparator else comparator.reversed()).thenBy { it.name }.thenBy { it.key.orEmpty() })
    }
    private var boundSummaryScope = session.repairSummaryScope
    private fun restorePrevious() {
        if(started) return
        val saved=session.readRepairSummary(processed)
        if(saved["time"].text.isBlank() || saved["queryMode"].text=="excel") return
        previous=saved
        val cached=repairSnapshotFromCache(previous["snapshot"],processed)
        if(previous["snapshot"].isObject && cached==null) return
        val span=runCatching { DateSpan(LocalDate.parse(previous["start"].text),LocalDate.parse(previous["end"].text)) }.getOrNull()
        if(processed && cached!=null && span==null) return
        if(cached!=null) snapshot=cached
        elapsedMillis=previous["elapsedMillis"].number.toLong()
        querySpan=span; customSpan=span; days=null; thisMonth=false
        queriedAt=previous["time"].text; showingPrevious=true; started=true
    }
    private fun saveSummary() {
        if(!started || showingPrevious || boundSummaryScope!=session.repairSummaryScope) return
        val documents=if(processed) snapshot.documents else snapshot.documents.filter { it.key in snapshot.executorChecks }
        val people=personnelWorkloads(documents)
        val value=Jv.obj("time" to queriedAt,"queryMode" to "api", "elapsedMillis" to elapsedMillis,
            "complete" to (snapshot.complete && uncheckedExecutors==0 && executorFailures==0 && (processed || documents.none { it.personKey==null })),
            "range" to (querySpan?.let { "${it.days.first} 至 ${it.days.second}" } ?: "待处理状态快照"),
            "start" to querySpan?.days?.first?.toString(),"end" to querySpan?.days?.second?.toString(),
            "snapshot" to repairSnapshotCache(snapshot),
            "people" to org.json.JSONArray(people.map { Jv.obj("key" to it.key,"name" to it.name,"sign" to it.sign,"finish" to it.finish,"processed" to it.processed) }))
        runCatching { session.writeRepairSummary(processed,value) }
    }
    private fun scopeChanged() {
        reset(); boundSummaryScope=session.repairSummaryScope; restorePrevious()
    }
    val uncheckedExecutors get() = if (processed) 0 else snapshot.documents.count { it.key !in snapshot.executorChecks }
    val executorFailures get() = snapshot.executorChecks.values.count { it.startsWith("失败") }
    val detailTotal get() = (snapshot.documents.map { it.key }.toSet() + snapshot.executorChecks.keys).size
    val expectedTotal: Long? get() {
        val values = snapshot.pages.map { it.expected ?: estimate?.counts?.get(it.state) }
        return if (values.all { it != null }) values.sumOf { it!!.toLong() } else null
    }
    var phases by mutableStateOf(setOf(ManagedPhase.Sign,ManagedPhase.Finish)); private set
    private var job: Job? = null
    private var generation = 0
    init {
        restorePrevious()
        viewModelScope.launch { session.scope.drop(1).collect { scopeChanged() } }
        viewModelScope.launch { session.changed.drop(1).collect { reset() } }
        viewModelScope.launch { session.queryAccounts?.changes?.drop(1)?.collect { scopeChanged() } }
    }
    fun pause() { saveSummary(); generation++; job?.cancel(); loading=false; resolving=false; checking=false; message="已暂停，保留已读取数据，可继续" }
    fun reset() {
        pause(); started=false; snapshot=emptySnapshot(); personKey=null; estimate=null
        showingPrevious=false
        querySpan=null; message=""; queriedAt=""; phases=setOf(ManagedPhase.Sign,ManagedPhase.Finish)
    }
    fun selectDays(value: Int) { if (days != value || customSpan!=null || thisMonth) { reset(); days=value; customSpan=null; thisMonth=false } }
    fun selectThisMonth() { if(!thisMonth) { reset(); days=null; customSpan=null; thisMonth=true } }
    fun selectSpan(value:DateSpan) { if(customSpan!=value || days!=null || thisMonth) { reset(); days=null; customSpan=value; thisMonth=false } }
    fun sort(ascending: Boolean) { this.ascending=ascending }
    fun showPerson(key: String?) { personKey=key }
    fun togglePhase(phase: ManagedPhase) { phases=if (phase in phases) phases-phase else phases+phase }
    fun leaveDashboard() { reset(); restorePrevious() }
    private fun launchRead(block: suspend (() -> Unit) -> Unit) {
        val isEstimate=checking
        val mine=++generation
        val scope=session.scope.value; val account=session.accountKey; val factory=session.currentFactoryId
        val queryRevision = session.queryRevision
        loading=true; message=""
        job=viewModelScope.launch {
            val before = elapsedMillis
            val begin = android.os.SystemClock.elapsedRealtime()
            val timer = if(isEstimate) null else launch { while (true) {
                elapsedMillis=before + android.os.SystemClock.elapsedRealtime() - begin
                delay(100)
            } }
            fun check() { if (mine!=generation || scope!=session.scope.value || account!=session.accountKey || factory!=session.currentFactoryId || queryRevision!=session.queryRevision || !session.signedIn) throw CancellationException() }
            try { check(); block(::check); check() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine==generation) message="读取失败：${e.message ?: "未知错误"}；当前结果不完整，可继续重试" }
            finally { timer?.cancel(); if (mine==generation) {
                val elapsed=before + android.os.SystemClock.elapsedRealtime() - begin
                if(!isEstimate) elapsedMillis=elapsed
                loading=false; checking=false; resolving=false; saveSummary()
            } }
        }
    }
    fun prepare() {
        if (loading || (processed && selectedSpan==null)) return
        val span=if (processed) selectedSpan else null
        checking=true
        launchRead { check ->
            val preview=estimateRepairRead(emptySnapshot().pages.map { it.state },check) { state ->
                session.readPage(ManagedKind.Fault.page,0,workloadFilters(ManagedKind.Fault,state,span),1)
            }
            check(); beginRead(preview,span)
        }
    }
    private fun beginRead(preview: RepairReadEstimate, span: DateSpan?) {
        reset(); estimate=preview; querySpan=span; started=true
        elapsedMillis=0
        readConcurrency=AppPreferences.effectiveRepairConcurrency
        snapshot=snapshot.copy(pages=snapshot.pages.map { it.copy(expected=preview.counts[it.state]) }, pageSize=AppPreferences.effectiveRepairPageSize)
        queriedAt=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        load()
    }
    fun load(retryFailures: Boolean = false) {
        if (loading || !started || showingPrevious) return
        if (snapshot.exhausted && uncheckedExecutors==0 && !retryFailures) return
        launchRead { check ->
            if (!snapshot.exhausted && !retryFailures) {
                snapshot=loadRepairPending(snapshot,budget=null,span=querySpan,checkScope=check,progress={ check(); snapshot=it }) { state,offset,limit ->
                    session.readPage(ManagedKind.Fault.page,offset,workloadFilters(ManagedKind.Fault,state,querySpan),limit)
                }
            }
            if (!processed) {
                resolving=true
                snapshot=resolveRepairExecutors(snapshot,retryFailures,concurrency=readConcurrency,checkScope=check,progress={ check(); snapshot=it },
                    detail={ id -> session.readRequest("fault/getAssetFaultDetailById",mapOf("faultId" to id)) },
                    repairInfo={ id -> latestRepair(session.readRequest("repair/getRepairsByFaultId",mapOf("faultId" to id))) ?: Jv.NULL })
            }
        }
    }
    fun openDocument(document: ManagedDocument, open: (String) -> Unit) { open(document.id) }
    override fun onCleared() { saveSummary(); super.onCleared() }
}
