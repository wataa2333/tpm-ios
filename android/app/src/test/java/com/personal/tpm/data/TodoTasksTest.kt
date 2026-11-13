package com.personal.tpm.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoTasksTest {
    private val maintenance = Jv.parse(
        """{"id":"maintenance","processInstanceName":"保养流程","name":"完成保养","data":{"maintainName":"主轴润滑","sn":"MT-123","asset":{"addressCode":"CNC-009","assetCode":"EQ-812","assetName":"数控机床"}}}"""
    )
    private val outbound = Jv.parse(
        """{"id":"outbound","processDefinitionKey":"ERP_OUTBOUND","data":{"outNumber":"OB-456","referenceSn":"FA-007","remark":"维修领料"}}"""
    )

    @Test fun matchesBusinessFieldsAndNamesWithOneSearchBox() {
        val tasks = listOf(maintenance, outbound)
        listOf("润滑", "MT-123", "cnc-009", "812", "数控", "完成保养", "润滑 cnc-009").forEach { query ->
            assertEquals(query, listOf(maintenance), searchTodoTasks(tasks, query))
        }
        listOf("OB-456", "fa-007", "领料").forEach { query ->
            assertEquals(query, listOf(outbound), searchTodoTasks(tasks, query))
        }
        assertTrue(searchTodoTasks(tasks, "未匹配").isEmpty())
    }

    @Test fun continuesBeyondTheFirstPageWhenTheServerReportsMoreRows() = runTest {
        val requestedOffsets = mutableListOf<Int>()
        val tasks = mutableListOf<Jv>()
        scanTodoTasks(fetch = { path, offset, limit ->
            assertEquals("process/pageUserTodoTask", path)
            assertEquals(100, limit)
            requestedOffsets.add(offset)
            val task = if (offset == 0) maintenance else outbound
            Jv(Jv.obj("content" to JSONArray().put(task.raw), "totalElements" to 2))
        }) { task, firstOccurrence -> if (firstOccurrence) tasks.add(task) }

        assertEquals(listOf(0, 1), requestedOffsets)
        assertEquals(listOf(outbound), searchTodoTasks(tasks, "OB-456"))
    }

    @Test fun overlappingPagesOnlyMarkEachTaskNewOnce() = runTest {
        val tasks = mutableListOf<Jv>()
        scanTodoTasks(fetch = { _, offset, _ ->
            val rows = JSONArray().put(maintenance.raw)
            if (offset > 0) rows.put(outbound.raw)
            Jv(Jv.obj("content" to rows, "last" to (offset > 0)))
        }) { task, firstOccurrence -> if (firstOccurrence) tasks.add(task) }

        assertEquals(listOf(maintenance, outbound), tasks)
    }

    @Test fun executorAndMachineMustMatchTheSameTask() = runTest {
        val missingExecutor = Jv.parse(
            """{"businessKey":"11","processDefinitionKey":"ASSET_FAULT:3","data":{"faultState":"SIGNED","asset":{"addressCode":"AB01"},"user":{"realName":"乙"}}}"""
        )
        val otherMachine = Jv.parse(
            """{"businessKey":"12","processDefinitionKey":"ASSET_FAULT:3","data":{"faultState":"SIGNED","asset":{"addressCode":"AB02"},"repairUser":{"realName":"甲"}}}"""
        )
        val receivingPerson = Jv.parse(
            """{"businessKey":"13","processDefinitionKey":"ASSET_FAULT:3","data":{"faultState":"REPORTED","asset":{"addressCode":"AB01"},"user":{"realName":"甲"}}}"""
        )
        val loaded = mutableListOf<String>()
        val matches = filterTaskConditions(
            listOf(missingExecutor, otherMachine, receivingPerson, outbound),
            listOf(SearchCondition("Machine", "AB01", exact = true), SearchCondition("Executor", "甲")),
        ) { id ->
            loaded += id
            Jv.parse("""[{"assetFault":{"faultId":"11"},"user":{"realName":"甲"}}]""")
        }
        assertEquals(listOf("11"), loaded)
        assertEquals(listOf(missingExecutor), matches)
    }
}
