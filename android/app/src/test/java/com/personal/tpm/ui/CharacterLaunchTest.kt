package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class CharacterLaunchTest {
    @Test fun pageStaysOutsideBeforeDownstrokeAndLandsExactly() {
        assertEquals(0f, launchPullProgress(0f), 0f)
        assertEquals(0f, launchPullProgress(1579f), 0f)
        assertEquals(1f, launchPullProgress(2400f), 0f)
        assertEquals(1f, launchPullProgress(3000f), 0f)
        val positions = (1580..2400 step 20).map { launchPullProgress(it.toFloat()) }
        assertTrue(positions.zipWithNext().all { (a, b) -> a <= b })
    }
}
