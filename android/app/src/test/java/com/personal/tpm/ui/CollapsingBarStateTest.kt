package com.personal.tpm.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsingBarStateTest {
    @Test fun downwardDragRevealsBeforeElasticChildCanConsumeIt() {
        val state = CollapsingBarState(56f)
        state.dragBy(-56f)
        val used = state.connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
        assertEquals(30f, used.y, 0f)
        assertEquals(26f, state.offset, 0f)
        // Even if the child consumes every remaining pixel, the header already expanded.
        state.connection.onPostScroll(Offset(0f, 40f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(26f, state.offset, 0f)
    }

    @Test fun directHeaderDragSharesBoundsAndLeavesUnusedDistanceForContent() {
        val state = CollapsingBarState(56f)
        assertEquals(-56f, state.dragBy(-100f), 0f)
        assertEquals(1f, state.fraction, 0f)
        assertEquals(56f, state.dragBy(100f), 0f)
        assertEquals(0f, state.fraction, 0f)
        assertEquals(0f, state.dragBy(20f), 0f)
    }
}
