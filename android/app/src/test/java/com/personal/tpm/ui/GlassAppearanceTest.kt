package com.personal.tpm.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GlassAppearanceTest {
    @Test fun changesSurvivePreferencesReloadAndResetOnlyAffectsGlassOptions() {
        val values = mutableMapOf<String, Any>("repairPageSize" to 100, "liquidGlass" to true,
            "glassBlur" to "High", "glassDispersion" to true, "glassShadow" to false,
            "glassSampling" to "Clear", "glassHighlight" to false, "glassRefraction" to false, "glassMotion" to false)
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args!![0]); proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString", "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) = preferences
        }
        try {
            AppPreferences.load(context)
            assertEquals(GlassBlur.Low, AppPreferences.glassEffects.blur)
            assertFalse(AppPreferences.glassEffects.dispersion)
            assertTrue(AppPreferences.glassEffects.shadow)
            assertEquals(GlassEffects(), AppPreferences.glassEffects)
            val chosen = GlassEffects(GlassBlur.High, highlight = false, refraction = false, dispersion = true, shadow = true, motion = false, sampling = GlassSampling.Balanced)
            AppPreferences.updateGlassEffects(chosen)
            AppPreferences.load(context)
            assertEquals(chosen, AppPreferences.glassEffects)
            AppPreferences.updateGlassEffects(GlassEffects())
            AppPreferences.load(context)
            assertEquals(GlassEffects(), AppPreferences.glassEffects)
            assertEquals(100, AppPreferences.repairPageSize)
            assertTrue(AppPreferences.liquidGlass)
        } finally {
            AppPreferences.updateGlassEffects(GlassEffects())
        }
    }

    @Test fun missingOrUnknownSavedBlurFallsBackToLow() {
        assertEquals(GlassBlur.Low, GlassBlur.fromKey(null))
        assertEquals(GlassBlur.Low, GlassBlur.fromKey("old-option"))
        GlassBlur.entries.forEach { assertEquals(it, GlassBlur.fromKey(it.name)) }
    }

    @Test fun missingOrUnknownSamplingUsesSaverAndEveryChoiceRoundTrips() {
        assertEquals(GlassSampling.Saver, GlassSampling.fromKey(null))
        assertEquals(GlassSampling.Saver, GlassSampling.fromKey("retired-option"))
        assertEquals(GlassSampling.Saver, GlassEffects().sampling)
        GlassSampling.entries.forEach { assertEquals(it, GlassSampling.fromKey(it.name)) }
    }

    @Test fun samplingCoversOddPixelEdgesAndNeverAllocatesAnEmptySource() {
        assertEquals(361, GlassSampling.Balanced.pixels(721))
        assertEquals(241, GlassSampling.Saver.pixels(721))
        assertEquals(721, GlassSampling.Clear.pixels(721))
        GlassSampling.entries.forEach {
            assertEquals(1, it.pixels(0))
            assertTrue(it.pixels(Int.MAX_VALUE) > 0)
            val pixels = it.pixels(721)
            assertTrue(pixels.toLong() * it.divisor >= 721)
            assertTrue((pixels - 1).toLong() * it.divisor < 721)
        }
    }

    @Test fun blurOffDoesNotDisableIndependentOpticalEffects() {
        val settings = GlassEffects(blur = GlassBlur.Off)
        assertTrue(settings.highlight)
        assertTrue(settings.refraction)
        assertTrue(settings.motion)
        assertFalse(settings.dispersion)
        assertTrue(settings.shadow)
        assertEquals(listOf(0f, 2f, 6f, 12f), GlassBlur.entries.map { it.radiusDp })
    }

    @Test fun primaryWhiteTextGetsReadableInkOnTransparentLightGlass() {
        val ink = Color(0xFF151515)
        assertEquals(ink, glassContentColor(Color.White, Color(0xFF007AFF), Color.White, ink))
        assertEquals(ink, glassContentColor(ink, Color(0xFFFFEAB6), Color.White, Color.Black))
    }

    @Test fun darkGlassKeepsLightInkAndDisabledAlpha() {
        val surface = Color(0xFF121212)
        assertEquals(Color.White, glassContentColor(Color.White, Color(0xFF007AFF), surface, Color.White))
        assertEquals(.38f, glassContentColor(Color.Black.copy(alpha = .38f), Color.Black, surface, Color.White).alpha, .005f)
    }
}
