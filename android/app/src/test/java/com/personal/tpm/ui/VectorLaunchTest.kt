package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

class VectorLaunchTest {
    @Test fun systemSnapshotUsesMeasuredPixelsWithoutAnotherInsetOrDensityScale() {
        val actual = Rect(120f, 800f, 360f, 1100f)
        assertEquals(Rect(120f, 776f, 360f, 1076f),
            launchIconDestination(240f, 300f, actual, Offset(0f,24f), Offset(500f,500f), 100f))
    }
    @Test fun fallbackKeepsImageAspectRatioOnDifferentScreens() {
        val image = launchIconDestination(200f,100f,null,Offset.Zero,Offset(500f,1000f),160f)
        assertEquals(2f, image.width/image.height,0f)
        assertEquals(Offset(500f,1000f), image.center)
    }
    @Test fun pageWaitsForDotToReachCenter() {
        for (step in 0..32) assertEquals(0f, launchRevealProgress(step / 100f), 0f)
        assertTrue(launchRevealProgress(.33f) > 0f)
        assertEquals(1f, launchRevealProgress(1f), 0f)
    }
    @Test fun circleCoversEveryScreenCornerAndNeverShrinks() {
        for ((w,h) in listOf(1080f to 2400f, 2400f to 1080f, 1200f to 1200f))
            assertTrue(launchRevealRadius(w,h) > hypot(w/2,h/2))
        assertTrue((0..100).map { launchRevealProgress(it/100f) }.zipWithNext().all { (a,b) -> a <= b })
    }
}
