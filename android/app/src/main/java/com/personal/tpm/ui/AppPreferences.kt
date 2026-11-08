package com.personal.tpm.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppPreferences {
    private var prefs: android.content.SharedPreferences? = null
    var liquidGlass by mutableStateOf(false); private set
    var showGlassIntroduction by mutableStateOf(false); private set
    fun dismissGlassIntroduction() { showGlassIntroduction = false; prefs?.edit()?.putInt("liquidGlassIntroductionVersion", com.personal.tpm.BuildConfig.VERSION_CODE)?.apply() }
    fun updateLiquidGlass(on: Boolean) { liquidGlass = on; prefs?.edit()?.putBoolean("liquidGlass", on)?.apply() }
    var glassEffects by mutableStateOf(GlassEffects()); private set
    fun updateGlassEffects(value: GlassEffects) {
        glassEffects = value
        prefs?.edit()?.putString("glassBlur", value.blur.name)
            ?.putBoolean("glassHighlight", value.highlight)?.putBoolean("glassRefraction", value.refraction)
            ?.putBoolean("glassDispersion", value.dispersion)?.putBoolean("glassShadow", value.shadow)
            ?.putBoolean("glassMotion", value.motion)?.putString("glassSampling", value.sampling.name)?.apply()
    }
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
        liquidGlass = prefs!!.getBoolean("liquidGlass", android.os.Build.VERSION.SDK_INT >= 31)
        showGlassIntroduction = prefs!!.getInt("liquidGlassIntroductionVersion", 0) < com.personal.tpm.BuildConfig.VERSION_CODE
        glassEffects = GlassEffects(
            blur = GlassBlur.fromKey(prefs!!.getString("glassBlur", null)),
            highlight = prefs!!.getBoolean("glassHighlight", true),
            refraction = prefs!!.getBoolean("glassRefraction", true),
            dispersion = prefs!!.getBoolean("glassDispersion", false),
            shadow = prefs!!.getBoolean("glassShadow", true),
            motion = prefs!!.getBoolean("glassMotion", true),
            sampling = GlassSampling.fromKey(prefs!!.getString("glassSampling", null)),
        )
        // Apply the requested light preset once when upgrading; later user adjustments remain persistent.
        if (prefs!!.getInt("glassPresetRevision", 0) < 108) {
            updateGlassEffects(glassEffects.copy(blur = GlassBlur.Low, dispersion = false))
            prefs!!.edit().putInt("glassPresetRevision", 108).apply()
        }
        if (prefs!!.getInt("glassPresetRevision", 0) < 110) {
            updateGlassEffects(glassEffects.copy(dispersion = false, shadow = true))
            prefs!!.edit().putInt("glassPresetRevision", 110).apply()
        }
        // Adopt the final requested preset once; subsequent manual choices still survive reloads.
        if (prefs!!.getInt("glassPresetRevision", 0) < 115) {
            updateGlassEffects(GlassEffects())
            prefs!!.edit().putInt("glassPresetRevision", 115).apply()
        }
        repairReadExperiment = prefs!!.getBoolean("repairReadExperiment", false)
        repairPageSize = prefs!!.getInt("repairPageSize", 500).takeIf { it in repairPageSizes } ?: 500
        repairConcurrency = prefs!!.getInt("repairConcurrency", 8).takeIf { it in repairConcurrencyLevels } ?: 8
    }
    fun updateRepairReadExperiment(on: Boolean) { repairReadExperiment = on; prefs?.edit()?.putBoolean("repairReadExperiment", on)?.apply() }
    fun updateRepairPageSize(value: Int) { require(value in repairPageSizes); repairPageSize = value; prefs?.edit()?.putInt("repairPageSize", value)?.apply() }
    fun updateRepairConcurrency(value: Int) { require(value in repairConcurrencyLevels); repairConcurrency = value; prefs?.edit()?.putInt("repairConcurrency", value)?.apply() }
}
