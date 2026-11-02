package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class QuickCompletionTest {
    @Test fun previousOrderResultRemainsAvailableInsideTheWizard() {
        assertNull(quickPreviousResult(emptyList()))
        assertNull(quickPreviousResult(listOf(QuickJobState.Running)))
        assertEquals("上一单已提交 · 1/1", quickPreviousResult(listOf(QuickJobState.Done)))
        assertEquals("上一单提交失败 · 0/1", quickPreviousResult(listOf(QuickJobState.Failed)))
        assertEquals("上一单结果待核对 · 0/1", quickPreviousResult(listOf(QuickJobState.Review)))
    }
    @Test fun newRunsExcludeInflightOrUncertainWritesButAllowFreshServerChecks() {
        assertTrue(quickBlocksNewRun(QuickJobState.Queued))
        assertTrue(quickBlocksNewRun(QuickJobState.Running))
        assertTrue(quickBlocksNewRun(QuickJobState.Review))
        assertFalse(quickBlocksNewRun(QuickJobState.Done))
        assertFalse(quickBlocksNewRun(QuickJobState.Failed))
    }
    @Test fun completedOrFailedJobsCannotLeaveAnIdleProgressBadge() {
        assertFalse(quickSubmissionActive(emptyList()))
        assertFalse(quickSubmissionActive(listOf(QuickJobState.Done, QuickJobState.Failed, QuickJobState.Review)))
        assertTrue(quickSubmissionActive(listOf(QuickJobState.Done, QuickJobState.Queued)))
        assertTrue(quickSubmissionActive(listOf(QuickJobState.Running)))
    }
    @Test fun completionWaitsForTheLastFormAndEveryBackgroundWrite() {
        assertFalse(quickCompletionReady(false, false, false, false))
        assertFalse(quickCompletionReady(true, true, false, false))
        assertFalse(quickCompletionReady(true, false, true, false))
        assertTrue(quickCompletionReady(true, false, false, false))
        assertFalse(quickCompletionReady(true, false, false, true))
    }
    @Test fun reviewAndFailuresAreNeverCountedAsSubmittedOrders() {
        val summary = quickRunSummary(listOf(QuickJobState.Done, QuickJobState.Done,
            QuickJobState.Failed, QuickJobState.Review), skipped = 3, signFailed = 1)
        assertEquals(2, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.review)
        assertEquals(3, summary.skipped)
        assertEquals(1, summary.signFailed)
        assertTrue(summary.message.contains("成功提交 2 单"))
        assertTrue(summary.message.contains("结果需要核对"))
    }
    @Test fun skippingEverythingStillReportsZeroSubmissions() {
        val summary = quickRunSummary(emptyList(), skipped = 5, signFailed = 0)
        assertEquals("成功提交 0 单，跳过 5 单。", summary.message)
    }
}
