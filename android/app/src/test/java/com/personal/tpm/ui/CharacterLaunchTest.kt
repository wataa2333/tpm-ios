package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class CharacterLaunchTest {
    @Test fun pageStaysOutsideBeforeDownstrokeAndLandsExactly() {
        assertEquals(0f, launchPullProgress(0f), 0f)
        assertEquals(0f, launchPullProgress(539f), 0f)
        assertEquals(1f, launchPullProgress(1100f), 0f)
        assertEquals(1f, launchPullProgress(3000f), 0f)
        val positions = (540..1100 step 20).map { launchPullProgress(it.toFloat()) }
        assertTrue(positions.zipWithNext().all { (a, b) -> a <= b })
    }
    @Test fun framesAdvanceAtSixtyMillisecondsAndHoldLastPose() {
        assertEquals(0, launchFrameIndex(59f))
        assertEquals(1, launchFrameIndex(60f))
        assertEquals(9, launchFrameIndex(540f))
        assertEquals(11, launchFrameIndex(1100f))
    }
    @Test fun waitingLoopNeverPlaysTheDownstroke() {
        assertEquals(0, launchWaitingFrame(0))
        assertEquals(8, launchWaitingFrame(480))
        for (time in 240L..120000L step 30) assertTrue(launchWaitingFrame(time) in 4..8)
        assertEquals(launchWaitingFrame(600), launchWaitingFrame(1080))
    }
}
