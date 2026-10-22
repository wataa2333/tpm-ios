package com.personal.tpm.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Owned by the dashboard back-stack entry, so child navigation cannot discard or refresh it. */
class PrecisionDashboardModel(private val owner: Session, private val queries: QueryAccounts) : ViewModel() {
    var snapshot by mutableStateOf<PrecisionSnapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var readAt by mutableStateOf(""); private set
    var search by mutableStateOf("")
    var grade by mutableStateOf<String?>(null)
    var days by mutableIntStateOf(30)
    var shown by mutableIntStateOf(20)
    val listState = LazyListState()
    val people = mutableStateMapOf<String, String>()
    private var bound: Pair<Int, Int>? = null
    private var generation = 0
    private var readJob: Job? = null
    private val peopleJobs = mutableMapOf<String, Job>()
    private val permits = Semaphore(3)

    fun ensureLoaded(scope: Int, revision: Int) {
        if (bound == (scope to revision)) return
        readJob?.cancel(); peopleJobs.values.forEach { it.cancel() }; peopleJobs.clear()
        bound = scope to revision; loading = false; snapshot = null; people.clear(); shown = 20
        refresh()
    }

    fun refresh() {
        if (loading) return
        val mine = ++generation
        peopleJobs.values.forEach { it.cancel() }; peopleJobs.clear(); people.clear()
        loading = true; error = ""
        val scope = owner.scope.value; val revision = queries.revision
        readJob = viewModelScope.launch {
            try {
                val fresh = loadPrecision(queries.source(owner))
                if (mine != generation || scope != owner.scope.value || revision != queries.revision) return@launch
                snapshot = fresh; shown = 20
                readAt = java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == generation) error = e.message ?: "读取失败" }
            finally { if (mine == generation) loading = false }
        }
    }

    fun matching(): List<Jv> = precisionSearch(snapshot?.rows.orEmpty(), search, grade)

    fun loadPeople(row: Jv) {
        if (loading) return
        val id = row["asset"]["assetId"].text
        if (id in people || id in peopleJobs) return
        val mine = generation
        peopleJobs[id] = viewModelScope.launch {
            try {
                val text = permits.withPermit { precisionPeople(queries.source(owner), id) }
                if (mine == generation) people[id] = text
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (mine == generation) people[id] = "检测／上传人：暂未读取到" }
        }
    }
}

fun precisionSearch(rows: List<Jv>, search: String, grade: String?): List<Jv> {
    val typed = search.trim()
    val typedGrade = typed.uppercase(java.util.Locale.ROOT).takeIf { it in precisionGrades }
    return rows.filter { row ->
        val result = row["analysisResult"].text.removePrefix("RESULT_")
        (grade == null || result == grade) && when {
            typed.isEmpty() -> true
            typedGrade != null -> result == typedGrade
            else -> listOf("assetCode", "assetName", "addressCode").any { row["asset"][it].text.contains(typed, ignoreCase = true) }
        }
    }.sortedWith(compareByDescending<Jv> {
        runCatching { LocalDate.parse(it["analysisDate"].text.take(10)).toEpochDay() }.getOrDefault(Long.MIN_VALUE)
    }.thenBy { it["asset"]["assetCode"].text })
}
