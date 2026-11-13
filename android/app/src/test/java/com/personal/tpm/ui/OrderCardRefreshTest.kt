package com.personal.tpm.ui

import com.personal.tpm.data.Jv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderCardRefreshTest {
    private fun rows(vararg ids: String) = ids.map { Jv(Jv.obj("id" to it)) }
    private fun cards(vararg ids: String) = keyedOrderCards(rows(*ids), "id")

    @Test fun survivingCardsKeepTheirKeysWhenRowsAboveThemDisappear() {
        val before = cards("A", "B", "C")
        val after = cards("B", "C")
        assertEquals(before.drop(1).map { it.key }, after.map { it.key })
        assertEquals(setOf(before.first().key), removedOrderCardKeys(before, after, lastPage = true))
    }

    @Test fun aFirstPageRefreshDoesNotAnimateRowsPushedPastTheSharedBoundary() {
        val before = cards("A", "B", "C", "D", "E")
        val after = cards("X", "A", "C")
        assertEquals(setOf(before[1].key), removedOrderCardKeys(before, after, lastPage = false))
        assertTrue(removedOrderCardKeys(before, cards("X", "Y"), lastPage = false).isEmpty())
    }

    @Test fun anEmptyCompleteResultCanRemoveAllIdentifiableCards() {
        val before = cards("A", "B")
        assertEquals(before.map { it.key }.toSet(), removedOrderCardKeys(before, emptyList(), lastPage = true))
    }

    @Test fun repeatedAndMissingIdsHaveUniqueKeysAndDoNotClaimARemoval() {
        val before = cards("same", "same", "", "", "single")
        assertEquals(before.size, before.map { it.key }.distinct().size)
        assertEquals(setOf(before.last().key), removedOrderCardKeys(before, emptyList(), lastPage = true))
    }

    @Test fun onlyReplacingExistingResultsForTheSameQueryCanAnimateRemoval() {
        val initial = OrderCardResults()
        val first = initial.replace(rows("A", "B"), "search", lastPage = false)
        assertFalse(first.canAnimateFrom(initial))
        assertFalse(first.append(rows("C"), lastPage = true).canAnimateFrom(first))
        assertFalse(first.replace(rows("C"), "new query", lastPage = true).canAnimateFrom(first))
        assertTrue(first.replace(rows("B"), "search", lastPage = true).canAnimateFrom(first))
    }
}
