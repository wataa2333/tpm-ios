package com.personal.tpm.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The one scale of motion of the app: five lengths and three curves. Every animation takes its length and curve from here (a screen never writes
 * a number of milliseconds of its own), so the same kind of change always takes the same time. The values can be changed on the developer page
 * (opened from 我的 by tapping its title ten times) and are saved.
 *
 * Lengths, by what moves:
 *   Instant 100 ms  a press, a tick, a small icon turning
 *   Fast    250 ms  something that fades or shrinks out of the way, a small slide (a subtitle, a count)
 *   Normal  300 ms  a state changing in place: a chip or a capsule changing colour, a title changing, a card closing
 *   Slow    450 ms  something opening or arriving: a card opening, a bar rising, a sheet of content fading in, the skeleton melting into cards
 *   Page    600 ms  a whole page sliding in or out
 *
 * Curves, by role (each can be set to one of [Curve]):
 *   Arrive  what comes in: a page sliding in, a bar rising, a card opening     (ease in and out, by default)
 *   Leave   what goes out: a page sliding out, a card closing                  (ease in and out)
 *   Change  a change that begins and ends on screen: colours, sizes, the rest  (ease in and out)
 *   Linear itself is only for what has no start and end: a turning icon, a loop that must join up; it is not one of the roles' defaults.
 */
object Motion {
    enum class Curve(val title: String, val easing: Easing) {
        Linear("线性", LinearEasing),
        Standard("慢快慢", FastOutSlowInEasing),
        Decelerate("先快后慢", CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),
        Accelerate("先慢后快", CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),
    }

    /** Names, defaults and the preference keys of the five lengths, from the shortest. */
    val tiers = listOf("Instant" to 100, "Fast" to 250, "Normal" to 300, "Slow" to 450, "Page" to 600)
    val tierTitles = listOf("瞬间（按压、勾选）", "快（渐隐、让路）", "常规（原地变化）", "慢（展开、升起）", "整页（滑入滑出）")
    val roleTitles = listOf("到来（页面滑入、展开）", "离开（页面滑出、收起）", "变化（变色、变大小）")
    val roleDefaults = listOf(Curve.Standard, Curve.Standard, Curve.Standard)

    private var prefs: SharedPreferences? = null
    private val lengths = List(5) { mutableIntStateOf(tiers[it].second) }
    private val curves = List(3) { mutableIntStateOf(roleDefaults[it].ordinal) }

    val Instant: Int get() = lengths[0].intValue
    val Fast: Int get() = lengths[1].intValue
    val Normal: Int get() = lengths[2].intValue
    val Slow: Int get() = lengths[3].intValue
    val Page: Int get() = lengths[4].intValue

    val Arrive: Easing get() = Curve.entries[curves[0].intValue].easing
    val Leave: Easing get() = Curve.entries[curves[1].intValue].easing
    val Change: Easing get() = Curve.entries[curves[2].intValue].easing

    fun length(index: Int): Int = lengths[index].intValue
    fun setLength(index: Int, millis: Int) { lengths[index].intValue = millis.coerceIn(40, 1200); prefs?.edit()?.putInt("motion_len_$index", lengths[index].intValue)?.apply() }
    fun curve(role: Int): Curve = Curve.entries[curves[role].intValue]
    fun setCurve(role: Int, value: Curve) { curves[role].intValue = value.ordinal; prefs?.edit()?.putInt("motion_curve_$role", value.ordinal)?.apply() }
    val isDefault: Boolean get() = lengths.indices.all { lengths[it].intValue == tiers[it].second } && curves.indices.all { curves[it].intValue == roleDefaults[it].ordinal }
    fun reset() { tiers.indices.forEach { setLength(it, tiers[it].second) }; roleDefaults.indices.forEach { setCurve(it, roleDefaults[it]) } }

    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("tpm_theme", Context.MODE_PRIVATE)
        prefs = saved
        lengths.indices.forEach { lengths[it].intValue = saved.getInt("motion_len_$it", tiers[it].second).coerceIn(40, 1200) }
        curves.indices.forEach { curves[it].intValue = saved.getInt("motion_curve_$it", roleDefaults[it].ordinal).coerceIn(0, Curve.entries.lastIndex) }
    }
}
