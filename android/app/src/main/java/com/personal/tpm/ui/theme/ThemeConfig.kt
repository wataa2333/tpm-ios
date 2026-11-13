package com.personal.tpm.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.tpm.R

/**
 * The one place the app's look is decided: a bright white theme with one accent colour, the shapes, the type and the colour of every
 * order state. Screens never write a colour of their own: they read MaterialTheme, [ThemeConfig.stateColor] or [ThemeConfig.approvalColor].
 *
 * The accent is chosen on the 我的 page from the Material colour palette (or typed as a hex colour) and is remembered. Everything
 * else (the tonal surfaces, the text-safe version of the accent, the dark scheme) is worked out from it here.
 * Android 10 has no dynamic colour, so nothing depends on the system wallpaper either.
 */
object ThemeConfig {
    enum class Mode(val title: String) { System("跟随系统"), Light("浅色"), Dark("深色") }


    /** The named accent colours offered alongside custom hex values. */
    data class Preset(val name: String, val color: Color)
    private val violetAccent = Color(0xFFA962CF)
    val presets = listOf(
        Preset("晴空蓝", Color(0xFF28B1FF)),
        Preset("哔哩哔哩粉", Color(0xFFFF6699)), Preset("基佬紫", violetAccent),
        Preset("傲娇红", Color(0xFFE53935)), Preset("咸蛋黄", Color(0xFFFFB300)),
        Preset("草苗绿", Color(0xFF43A047)), Preset("水鸭青", Color(0xFF00897B)),
    )

    /**
     * The standard of tones (light look; the dark look follows the same order). Surfaces, from the back to the front:
     *   L0 page      #F0F1F4   the background of every page
     *   L1 card      #FFFFFF   cards, rows, the top bar and the bottom bar (a step lighter than the page, so a card always stands off it)
     *   L2 inset     #F5F6F8   a quiet block inside a card (a note, an expanded detail)
     *   L3 field     #EEF0F3   chips, text fields, tracks, the unselected capsule
     *   L4 pressed   #E4E7EB   a pressed or selected-without-colour state
     *   hairline     #E3E5EA   dividers
     * Text, from the strongest to the faintest:
     *   T1 #1B1E23  the title of a card, the figure that matters       (onSurface)
     *   T2 #4A5059  the body of a card, the labels beside values       (onSurfaceVariant)
     *   T3 #8A909B  hints, units, times, empty placeholders            (outline)
     * The theme colour is for the few things that must catch the eye, and nothing else: the main action (the bottom action bar, the + button),
     * the chosen one among several (capsule, tick, tab), a figure to act on (badge, entered value, link). Titles, labels and icons are T1..T3.
     */
    private val page = Color(0xFFF0F1F4)
    private val card = Color(0xFFFFFFFF)
    private val inset = Color(0xFFF5F6F8)
    private val field = Color(0xFFEEF0F3)
    private val pressed = Color(0xFFE4E7EB)
    private val hairline = Color(0xFFE3E6EA)
    private val text1 = Color(0xFF1B1E23)
    private val text2 = Color(0xFF4A5059)
    private val text3 = Color(0xFF8A909B)

    /** Five steps of interface size (on top of the automatic fit to the width of the screen); 标准 is the old 紧凑, 舒适 the old 标准. */
    val sizeLevels = listOf("紧凑", "标准", "舒适", "宽松", "特宽")
    private val sizeFactors = listOf(0.82f, 0.90f, 1.0f, 1.10f, 1.20f)

    private var prefs: SharedPreferences? = null
    private var accentArgb by mutableIntStateOf(presets[0].color.toArgb())
    private var sizeStep by mutableIntStateOf(1)
    private var modeName by mutableStateOf(Mode.System.name)

    val accent: Color get() = Color(accentArgb)
    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == modeName } ?: Mode.System
        set(value) { modeName = value.name; prefs?.edit()?.putString("mode", value.name)?.apply() }

    var sizeLevel: Int
        get() = sizeStep
        set(value) { sizeStep = value.coerceIn(0, sizeLevels.lastIndex); prefs?.edit()?.putInt("size5", sizeStep)?.apply() }
    val sizeFactor: Float get() = sizeFactors[sizeStep]
    private var highRefreshValue by mutableStateOf(true)
    /** Whether the app asks the system for the screen's highest refresh rate (it may refuse). */
    var highRefresh: Boolean
        get() = highRefreshValue
        set(value) { highRefreshValue = value; prefs?.edit()?.putBoolean("highRefresh", value)?.apply() }

    /** The step's factor, and the fit to the screen width (a 392 dp wide phone is the reference): the Activity multiplies the phone's density by both. */
    fun scaleFactor(step: Int): Float = sizeFactors[step.coerceIn(0, sizeFactors.lastIndex)]
    fun fitToWidth(smallestWidthDp: Int): Float = (smallestWidthDp.coerceAtMost(600) / 392f).coerceIn(0.85f, 1.2f)

    /**
     * The part of the screen the app uses, set by hand: how many dp are left unused at the top, the bottom, the left and the right (for a
     * screen with a damaged edge, or a cut-out the system does not report). Saved, and used at every start.
     */
    private var areaTop by mutableIntStateOf(0)
    private var areaBottom by mutableIntStateOf(0)
    private var areaStart by mutableIntStateOf(0)
    private var areaEnd by mutableIntStateOf(0)
    val displayTop: Int get() = areaTop
    val displayBottom: Int get() = areaBottom
    val displayStart: Int get() = areaStart
    val displayEnd: Int get() = areaEnd
    const val DISPLAY_AREA_MAX = 160
    fun setDisplayArea(top: Int = areaTop, bottom: Int = areaBottom, start: Int = areaStart, end: Int = areaEnd) {
        areaTop = top.coerceIn(0, DISPLAY_AREA_MAX); areaBottom = bottom.coerceIn(0, DISPLAY_AREA_MAX)
        areaStart = start.coerceIn(0, DISPLAY_AREA_MAX); areaEnd = end.coerceIn(0, DISPLAY_AREA_MAX)
        prefs?.edit()?.putInt("areaTop", areaTop)?.putInt("areaBottom", areaBottom)?.putInt("areaStart", areaStart)?.putInt("areaEnd", areaEnd)?.apply()
    }
    val hasCustomArea: Boolean get() = areaTop + areaBottom + areaStart + areaEnd > 0


    /** The theme colour as chosen (the surfaces are always black and white; this is the only colour besides the order states). */
    val effectiveAccent: Color get() = accent


    /** Choosing a colour by hand: it is kept, and the random colour at each start is switched off. */
    fun setAccent(color: Color) {
        accentArgb = color.copy(alpha = 1f).toArgb()
        randomValue = false
        prefs?.edit()?.putInt("accent", accentArgb)?.putBoolean("random", false)?.apply()
    }

    private var randomValue by mutableStateOf(true)
    /** Whether the colour changes by itself at every start of the app. */
    val randomOnLaunch: Boolean get() = randomValue

    /** Another colour of the palette, chosen at random (not the one in use). */
    fun shuffleAccent() {
        val others = presets.filter { it.color.toArgb() != accentArgb }
        accentArgb = others.random().color.toArgb()
    }

    /** Turns the random colour at every start on (and shows one now) or off (the colour now in use is kept for good). */
    fun setRandomOnLaunch(on: Boolean) {
        randomValue = on
        if (on) shuffleAccent()
        prefs?.edit()?.putBoolean("random", on)?.apply()
        if (!on) prefs?.edit()?.putInt("accent", accentArgb)?.apply()
    }

    /** Read the saved choices once at start-up. */
    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("tpm_theme", Context.MODE_PRIVATE)
        prefs = saved
        accentArgb = when (val storedAccent = saved.getInt("accent", accentArgb)) {
            0xFFEC407A.toInt() -> 0xFFFF6699.toInt()
            0xFF8E24AA.toInt() -> violetAccent.toArgb()
            0xFFFB8C00.toInt() -> presets.first().color.toArgb()
            else -> storedAccent
        }
        // By default the colour changes at every start, the look follows the system (light or dark), and the loading animation is the wave.
        randomValue = saved.getBoolean("random", true)
        if (randomValue) shuffleAccent()
        // A new key for the size: the old one meant different steps.
        areaTop = saved.getInt("areaTop", 0).coerceIn(0, DISPLAY_AREA_MAX); areaBottom = saved.getInt("areaBottom", 0).coerceIn(0, DISPLAY_AREA_MAX)
        areaStart = saved.getInt("areaStart", 0).coerceIn(0, DISPLAY_AREA_MAX); areaEnd = saved.getInt("areaEnd", 0).coerceIn(0, DISPLAY_AREA_MAX)
        highRefreshValue = saved.getBoolean("highRefresh", true)
        sizeStep = saved.getInt("size5", sizeStep).coerceIn(0, sizeLevels.lastIndex)
        modeName = saved.getString("mode", Mode.System.name) ?: Mode.System.name
    }

    // ---- colour arithmetic ----

    private fun Color.luminanceValue(): Float {
        fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
    }
    fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = a.luminanceValue().let { x -> b.luminanceValue().let { y -> if (x > y) x to y else y to x } }
        return (hi + 0.05f) / (lo + 0.05f)
    }
    /** Black or white, whichever reads better on this colour. */
    fun onColor(color: Color): Color = if (color.luminanceValue() < ON_COLOR_THRESHOLD) Color.White else Color.Black
    /**
     * The one threshold for the text on any coloured block: a colour darker than this (relative luminance) gets white text, a lighter one gets
     * black. It is set high enough that mid-dark colours (red, green, orange, the blues) read with white, and only truly light ones (yellow, lime) take black.
     */
    const val ON_COLOR_THRESHOLD = 0.42f
    /** The accent darkened until text in it reads on white (a pale yellow accent would otherwise be unreadable as a link). */
    /** The colour as ink on white: darkened until it reads (for the few texts set in the theme colour). */
    fun readable(color: Color): Color = textSafe(color)
    private fun textSafe(color: Color, against: Color = Color.White, minContrast: Float = 4.5f): Color {
        var result = color
        var step = 0
        while (contrast(result, against) < minContrast && step < 20) { result = lerp(result, Color.Black, 0.08f); step++ }
        return result
    }

    private val pageDark = Color(0xFF101318)

    fun lightScheme(accent: Color): ColorScheme {
        // The accent as chosen, for buttons, capsules, ticks and badges: it is only darkened when it is so pale (yellow, lime) that a button of it
        // would barely show on white. Text in the accent colour uses [readable], which darkens it further, so the fills are not made dull for the
        // sake of the text.
        val primary = textSafe(accent, minContrast = 2.2f)
        return lightColorScheme(
            primary = primary, onPrimary = onColor(primary),
            // The top bar is a card-white layer on the grey page, with the dark text of the standard.
            primaryContainer = page, onPrimaryContainer = text1,
            secondary = text2, onSecondary = Color.White,
            secondaryContainer = lerp(card, accent, 0.28f), onSecondaryContainer = text1,
            tertiary = Color(0xFF8A5A2B),
            background = page, onBackground = text1,
            surface = page, onSurface = text1, onSurfaceVariant = text2,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = card, surfaceContainerLow = inset, surfaceContainer = field,
            surfaceContainerHigh = pressed, surfaceContainerHighest = Color(0xFFDADDE3),
            outline = text3, outlineVariant = hairline,
            error = Color(0xFFBA1A1A),
        )
    }

    fun darkScheme(accent: Color): ColorScheme {
        val primary = lerp(accent, Color.White, 0.30f)
        return darkColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = pageDark, onPrimaryContainer = Color(0xFFE6E7EB),
            secondary = Color(0xFFB4B8C0), tertiary = Color(0xFFE0B48A),
            secondaryContainer = lerp(Color(0xFF1A1D23), accent, 0.25f), onSecondaryContainer = Color(0xFFE6E7EB),
            background = pageDark, onBackground = Color(0xFFE6E7EB),
            surface = pageDark, onSurface = Color(0xFFE6E7EB), onSurfaceVariant = Color(0xFFB4B8C0),
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color(0xFF1A1D23), surfaceContainerLow = Color(0xFF16191F), surfaceContainer = Color(0xFF22262E),
            surfaceContainerHigh = Color(0xFF2B303A), surfaceContainerHighest = Color(0xFF353B47),
            outline = Color(0xFF8A9099), outlineVariant = Color(0xFF30343D),
            error = Color(0xFFFFB4AB),
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )

    /** Bundled Google Sans for Latin text; unsupported glyphs use the system fallback. */
    val fontFamily = FontFamily(
        Font(R.font.google_sans_flex_light, FontWeight.Light),
        Font(R.font.google_sans_regular, FontWeight.Normal),
        Font(R.font.google_sans_medium, FontWeight.Medium),
        Font(R.font.google_sans_semibold, FontWeight.SemiBold),
        Font(R.font.google_sans_bold, FontWeight.Bold),
    )
    // Qualify the object property: the extension receiver also has a nullable fontFamily.
    private fun TextStyle.withFont() = copy(fontFamily = ThemeConfig.fontFamily)
    val typography: Typography = Typography().let {
        it.copy(
            displayLarge = it.displayLarge.withFont(), displayMedium = it.displayMedium.withFont(), displaySmall = it.displaySmall.withFont(),
            headlineLarge = it.headlineLarge.withFont(), headlineMedium = it.headlineMedium.withFont(), headlineSmall = it.headlineSmall.withFont(),
            titleLarge = it.titleLarge.withFont(), titleMedium = it.titleMedium.withFont(), titleSmall = it.titleSmall.withFont(),
            bodyLarge = it.bodyLarge.withFont().copy(lineHeight = 24.sp), bodyMedium = it.bodyMedium.withFont().copy(lineHeight = 22.sp), bodySmall = it.bodySmall.withFont().copy(lineHeight = 20.sp),
            labelLarge = it.labelLarge.withFont(), labelMedium = it.labelMedium.withFont(), labelSmall = it.labelSmall.withFont(),
        )
    }

    /** The type of the form and process pages: a step smaller than the lists, one size for text across them, and a tighter line. */
    val compactTypography: Typography = typography.let { t ->
        fun TextStyle.sized(size: Float) = copy(fontSize = size.sp, lineHeight = (size * 1.5f).sp)
        t.copy(
            bodyLarge = t.bodyLarge.sized(14f), bodyMedium = t.bodyMedium.sized(13f), bodySmall = t.bodySmall.sized(11.5f),
            titleMedium = t.titleMedium.sized(15f), titleSmall = t.titleSmall.sized(13.5f),
            labelLarge = t.labelLarge.sized(13f), labelMedium = t.labelMedium.sized(12f), labelSmall = t.labelSmall.sized(11f),
        )
    }

    /** The colour of an ERP approval state ("待审批", "审批中", ...), by the server's code. */
    fun approvalColor(code: String, dark: Boolean): Color {
        val (light, night) = when (code) {
            "PENDING", "APPROVED" -> Color(0xFFD97706) to Color(0xFFFFB454)
            "EXECUTING" -> Color(0xFF7C3AED) to Color(0xFFC7A6FF)
            "CONFIRM" -> Color(0xFF0E8F8F) to Color(0xFF5EDBDB)
            "REFUSE" -> Color(0xFFD92D20) to Color(0xFFFF8A80)
            "AGREE", "FINISHED" -> Color(0xFF2E8B57) to Color(0xFF7FDCA5)
            else -> Color(0xFF6F7280) to Color(0xFFB6B9C8)
        }
        return if (dark) night else light
    }

    /** The colour of an order state, by the server's state code, the same meaning as on iOS. */
    fun stateColor(code: String, dark: Boolean): Color {
        val (light, night) = when (code) {
            "UNTREATED" -> Color(0xFF6F7280) to Color(0xFFB6B9C8)
            "REPORTED" -> Color(0xFFD97706) to Color(0xFFFFB454)
            "RECEIVED" -> Color(0xFFD92D20) to Color(0xFFFF8A80)
            "SIGNED" -> Color(0xFF7C3AED) to Color(0xFFC7A6FF)
            "REPAIRED", "MAINTAINED", "PRECISED" -> Color(0xFF0E8F8F) to Color(0xFF5EDBDB)
            "VERIFIED" -> Color(0xFFD6336C) to Color(0xFFFF8FB5)
            "RESOLVED" -> Color(0xFF8A5A2B) to Color(0xFFE0B48A)
            "FINISHED" -> Color(0xFF2E8B57) to Color(0xFF7FDCA5)
            else -> Color(0xFF1B5FCB) to Color(0xFFB0C6FF)
        }
        return if (dark) night else light
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeConfig.mode) {
        ThemeConfig.Mode.System -> isSystemInDarkTheme()
        ThemeConfig.Mode.Light -> false
        ThemeConfig.Mode.Dark -> true
    }
    val accent = ThemeConfig.effectiveAccent
    // The interface size is applied by the Activity itself (MainActivity.attachBaseContext sets the density of its resources), so every window of
    // the app (pages, sheets, dialogs, pickers) is drawn at the same size and nothing here has to scale again.
    run {
        MaterialTheme(
            colorScheme = if (dark) ThemeConfig.darkScheme(accent) else ThemeConfig.lightScheme(accent),
            shapes = ThemeConfig.shapes,
            typography = ThemeConfig.typography,
            content = content,
        )
    }
}
