package com.personal.tpm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WorkflowTest {
    private fun node(json: String) = Jv.parse(json)

    @Test fun historyIsOrderedByTimeThenByStep() {
        val ordered = orderedHistory(listOf(
            node("""{"activityId":"B","activityType":"userTask","endTime":"x","sortTime":2}"""),
            node("""{"activityId":"START","activityType":"startEvent","sortTime":1}"""),
            node("""{"activityId":"A","activityType":"userTask","sortTime":1}"""),
        ))
        assertEquals(listOf("START", "A", "B"), ordered.map { it["activityId"].text })
    }

    @Test fun aRunningTaskReportedTwiceKeepsTheCopyWithComments() {
        val result = uniqueRunningUserTasks(listOf(
            node("""{"activityType":"userTask","activityId":"T","taskId":"1","comments":[]}"""),
            node("""{"activityType":"userTask","activityId":"T","taskId":"1","comments":[{"message":"请尽快"}]}"""),
        ))
        assertEquals(1, result.size)
        assertEquals(1, result[0]["comments"].array.size)
    }

    @Test fun timePrefixAndAssigneesFollowTheNode() {
        assertEquals("当前", historyTimePrefix(node("""{"activityType":"userTask"}""")))
        assertEquals("完成", historyTimePrefix(node("""{"activityType":"userTask","endTime":"2026-10-17 10:00"}""")))
        assertEquals(listOf("C001", "C002"), historyAssigneeNames(node("""{"assignees":"[C001, C002]"}""")))
        assertEquals(listOf("发起人"), historyAssigneeNames(node("""{"assignee":"${'$'}{initiator}"}""")))
    }

    @Test fun durationAndRelativeTimeAreReadable() {
        assertEquals("1时1分1秒", historyDuration(3_661_000.0))
        val now = LocalDateTime.of(2026, 10, 17, 12, 0)
        assertEquals("3天前", relativeTimeText("2026-10-14 12:00", now))
        assertEquals("刚刚", relativeTimeText("2026-10-17 12:00", now))
        assertTrue(relativeTimeText("", now).isEmpty())
    }

    @Test fun conditionTagIsHiddenForARunningSubmit() {
        assertTrue(!showsConditionTag(node("""{"activityType":"userTask","condition":"提交"}""")))
        assertTrue(showsConditionTag(node("""{"activityType":"userTask","condition":"提交","endTime":"x"}""")))
    }
}
