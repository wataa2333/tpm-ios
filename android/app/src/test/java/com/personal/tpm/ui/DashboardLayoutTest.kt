package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardLayoutTest {
    @Test fun sectionPreferencesKeepOrderAndAppendNewSectionsWithoutDuplicates() {
        assertEquals(listOf("quick", "stats", "assets"), normalizedSectionOrder(listOf("quick", "quick", "removed"), listOf("stats", "assets", "quick")))
        assertEquals(listOf("repair", "maintain"), normalizedSectionOrder(listOf(""), listOf("repair", "maintain")))
    }
    @Test fun chosenCategoryCentersWhenPossibleAndClampsAtBothEnds() {
        assertEquals(250, centeredChoiceScroll(350, 100, 300, 600))
        assertEquals(0, centeredChoiceScroll(0, 100, 300, 600))
        assertEquals(600, centeredChoiceScroll(850, 100, 300, 600))
        assertEquals(0, centeredChoiceScroll(0, 80, 300, 0))
    }
}
