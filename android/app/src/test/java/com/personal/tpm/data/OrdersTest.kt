package com.personal.tpm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OrdersTest {
    private fun fault(state: String, returned: Any? = null) = Jv.parse(
        """{"faultState":"$state","faultName":"X",${if (returned != null) "\"returnOrder\":$returned," else ""}"asset":{"assetCode":"A1"}}"""
    )

    @Test fun sentBackOrdersCarryTheirKindInTheStateLabel() {
        assertEquals("待受理（退单）", faultStateLabel(fault("REPORTED", true)))
        assertEquals("报修（驳回）", faultStateLabel(fault("UNTREATED", true)))
        assertEquals("待受理", faultStateLabel(fault("REPORTED", false)))
        assertEquals("待受理", faultStateLabel(fault("REPORTED")))
        assertEquals("待签到", faultStateLabel(fault("SIGNED", true)))
        assertEquals("退单", faultReturnKind(fault("REPORTED", "\"true\"")))
        assertNull(faultReturnKind(fault("RECEIVED", true)))
    }

    @Test fun repairNoteIsOnlyAskedWhereItCanExist() {
        assertTrue(faultMayHaveRepairNote(fault("REPAIRED")))
        assertTrue(faultMayHaveRepairNote(fault("REPORTED", true)))
        assertTrue(faultMayHaveRepairNote(fault("SIGNED")))
    }

    @Test fun latestRepairIsTheFirstOfTheList() {
        assertEquals("备件不到位", latestRepair(Jv.parse("""[{"content":"备件不到位"},{"content":"旧"}]"""))?.get("content")?.text)
        assertEquals("a", latestRepair(Jv.parse("""{"content":[{"content":"a"}]}"""))?.get("content")?.text)
        assertNull(latestRepair(Jv.parse("[]")))
    }

    @Test fun dateSpanOrdersItsDaysAndFormsTheServerFilter() {
        val span = DateSpan(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 10))
        assertEquals("2026-10-10 至 2026-10-17", span.summary)
        val filter = Jv(dateBetween("faultTime", span))
        assertEquals("dateBetween", filter["operator"].text)
        assertEquals(listOf("2026-10-10", "2026-10-17"), filter["value"].array.map { it.text })
        assertEquals("2026-10-10", DateSpan(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)).summary)
    }

    @Test fun searchFiltersFollowTheFieldAndTheRange() {
        val filters = faultSearchFilters("A-F08", FaultSearchField.Machine, DateSpan(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
        assertEquals(2, filters.size)
        assertEquals("asset.addressCode", Jv(filters[0])["field"].text)
        assertEquals("like", Jv(filters[0])["operator"].text)
        assertTrue(faultSearchFilters("", FaultSearchField.Name, null).isEmpty())
    }

    @Test fun jvReadsMissingKeysAsEmptyAndDatesInBothShapes() {
        val value = Jv.parse("""{"a":{"b":5},"t":[2026,10,7,9,5],"s":"2026-10-17 10:00","n":1.5,"i":3}""")
        assertEquals("", value["x"]["y"].text)
        assertEquals("5", value.at("a.b").text)
        assertEquals("2026-10-07 09:05", value["t"].dateText)
        assertEquals("2026-10-17 10:00", value["s"].dateText)
        assertEquals("3", value["i"].text)
        assertEquals("1.5", value["n"].text)
        assertEquals(3, serverPageCount(Jv.parse("""{"totalElements":3}""")))
        assertNull(serverPageCount(Jv.parse("{}")))
    }

    @Test fun cardFactsListTheOrdersFields() {
        val facts = faultCardFacts(Jv.parse("""{"faultName":"换轴承","asset":{"assetCode":"A1","assetName":"CNC"},"applyUser":{"realName":"张三"}}""")).toMap()
        assertEquals("张三", facts["报修人"])
        assertEquals("A1", facts["资产编码"])
    }
}
