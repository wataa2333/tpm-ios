package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class ChartGesturesTest {
    @Test fun verticalAndDiagonalSingleFingerDragsBelongToPage() {
        assertEquals(ChartGestureIntent.Page,chartGestureIntent(1,4f,20f,8f))
        assertEquals(ChartGestureIntent.Page,chartGestureIntent(1,20f,-20f,8f))
        assertEquals(ChartGestureIntent.Undecided,chartGestureIntent(1,2f,3f,8f))
    }
    @Test fun horizontalDraggingAndTwoFingerZoomBelongToChart() {
        assertEquals(ChartGestureIntent.Chart,chartGestureIntent(1,-20f,4f,8f))
        assertEquals(ChartGestureIntent.Chart,chartGestureIntent(2,0f,0f,8f))
        assertEquals(ChartGestureIntent.Undecided,chartGestureIntent(0,100f,0f,8f))
    }
}
