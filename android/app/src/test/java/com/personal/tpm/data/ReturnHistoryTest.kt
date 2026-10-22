package com.personal.tpm.data

import org.junit.Assert.*
import org.junit.Test

class ReturnHistoryTest {
    @Test fun latestCompletedReturnWinsOverCurrentCandidates() {
        val nodes = listOf(
            Jv.parse("""{"activityType":"userTask","condition":"退单","endTime":"2026-10-20","sortTime":1,"assignee":"old"}"""),
            Jv.parse("""{"activityType":"userTask","condition":"退单","endTime":"2026-10-20","sortTime":2,"assignee":"actual"}"""),
            Jv.parse("""{"activityType":"userTask","condition":"退单","sortTime":3,"candidateUsers":"candidate"}"""))
        assertEquals(listOf("actual"), latestReturnActors(nodes))
    }
    @Test fun candidatesAreNeverShownAsReturnActors() {
        assertTrue(latestReturnActors(listOf(Jv.parse("""{"activityType":"userTask","condition":"退单","endTime":"2026-10-20","candidateUsers":"not-actor"}"""))).isEmpty())
    }
}
