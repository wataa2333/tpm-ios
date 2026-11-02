package com.personal.tpm.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppPreferences {
    private var prefs: android.content.SharedPreferences? = null
    val repairPageSizes = listOf(50, 100, 200, 500)
    val repairConcurrencyLevels = listOf(1, 3, 5, 8)
    var repairReadExperiment by mutableStateOf(false); private set
    var repairPageSize by mutableStateOf(500); private set
    var repairConcurrency by mutableStateOf(8); private set
    val effectiveRepairPageSize get() = if (repairReadExperiment) repairPageSize else 500
    val effectiveRepairConcurrency get() = if (repairReadExperiment) repairConcurrency else 8
    fun load(context: Context) {
        prefs = context.getSharedPreferences("ui_options", Context.MODE_PRIVATE)
        prefs!!.edit().remove("repairExcelMode").apply()
        repairReadExperiment = prefs!!.getBoolean("repairReadExperiment", false)
        repairPageSize = prefs!!.getInt("repairPageSize", 500).takeIf { it in repairPageSizes } ?: 500
        repairConcurrency = prefs!!.getInt("repairConcurrency", 8).takeIf { it in repairConcurrencyLevels } ?: 8
    }
    fun updateRepairReadExperiment(on: Boolean) { repairReadExperiment = on; prefs?.edit()?.putBoolean("repairReadExperiment", on)?.apply() }
    fun updateRepairPageSize(value: Int) { require(value in repairPageSizes); repairPageSize = value; prefs?.edit()?.putInt("repairPageSize", value)?.apply() }
    fun updateRepairConcurrency(value: Int) { require(value in repairConcurrencyLevels); repairConcurrency = value; prefs?.edit()?.putInt("repairConcurrency", value)?.apply() }
}
