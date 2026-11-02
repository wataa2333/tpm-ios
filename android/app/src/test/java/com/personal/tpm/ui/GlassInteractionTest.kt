package com.personal.tpm.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class GlassInteractionTest {
    @Test fun fullWidthButtonsInflateByTheSameDistanceAsNarrowButtons() {
        val narrow = glassScale(Size(100f, 48f), 4f, Offset.Zero)
        val wide = glassScale(Size(1000f, 48f), 4f, Offset.Zero)
        assertEquals(4f, (narrow.x - 1f) * 100f, .001f)
        assertEquals(4f, (wide.x - 1f) * 1000f, .001f)
        assertEquals(narrow.y, wide.y, .001f)
        assertEquals(Offset(1f, 1f), glassScale(Size.Zero, 4f, Offset.Zero))
    }

    @Test fun pressAndDragStayInsideEightDpOfReservedSpace() {
        val drag = glassDragOffset(Offset(10000f, -10000f), 10f)
        val scale = glassScale(Size(320f, 48f), 4f, drag)
        assertTrue((scale.x - 1f) * 320f / 2f + kotlin.math.abs(drag.x) * .35f <= 8f)
        assertTrue((scale.y - 1f) * 48f / 2f + kotlin.math.abs(drag.y) * .35f <= 8f)
    }

    @Test fun transparentButtonUsesThemeInsteadOfBlackRgb() {
        val theme = Color(0xFFFFEAB6)
        assertEquals(theme, glassTintColor(Color.Transparent, theme))
        assertEquals(theme, glassTintColor(Color.Unspecified, theme))
        val disabled = Color(0x1F445566)
        assertEquals(disabled, glassTintColor(disabled, theme))
    }

    @Test fun draggingTabsSelectsTheTabUnderTheFingerAndClampsAtEdges() {
        assertEquals(0, glassTabIndex(-100f, 400f, 4))
        assertEquals(0, glassTabIndex(99f, 400f, 4))
        assertEquals(1, glassTabIndex(100f, 400f, 4))
        assertEquals(2, glassTabIndex(250f, 400f, 4))
        assertEquals(3, glassTabIndex(500f, 400f, 4))
        assertEquals(3, glassTabIndex(700f, 800f, 4))
        assertEquals(0, glassTabIndex(1f, 0f, 4))
        assertEquals(0, glassTabIndex(Float.NaN, 400f, 4))
    }

    @Test fun elasticMotionIsBoundedAndRejectsInvalidCoordinates() {
        assertEquals(Offset(10f, -10f), glassDragOffset(Offset(1000f, -1000f), 10f))
        assertEquals(Offset(6f, -3f), glassDragOffset(Offset(20f, -10f), 10f))
        assertEquals(Offset.Zero, glassDragOffset(Offset(Float.NaN, Float.POSITIVE_INFINITY), 10f))
    }
}
