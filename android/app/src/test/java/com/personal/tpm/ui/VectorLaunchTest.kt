package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class VectorLaunchTest {
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
