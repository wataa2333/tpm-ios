package com.personal.tpm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepairTextTest {
    @Test fun machineCodesAreReadFromLineStartsWithoutMistakingHeadersAndDates() {
        val lines = listOf(
            "@群成员", "故障机台明细", "CY41092-3夹", "DB53  大面刀纹  10/31 B",
            "50062/5-7夹", "467/6", "BJ25待换X导轨", "Cy00771-1夹", "Bl08所有刀摆大",
            "6.5夹", "BF02R角轮廓度NG", "BH07：待修复", "  D-E59 待签到",
        )
        assertEquals(listOf("DB53", "BJ25", "BL08", "BF02", "BH07", "D-E59"),
            lines.map(::reportMachineCode).filter(String::isNotEmpty))
    }

    @Test fun compactMachineCodesGetOneHyphenAndOtherCodesKeepTheirFormat() {
        assertEquals("D-B57", normalizeReportMachineCode("db57"))
        assertEquals("D-B57", normalizeReportMachineCode(" D-B57 "))
        assertEquals("LINE-DB57", normalizeReportMachineCode("line-db57"))
        assertEquals("DB570", normalizeReportMachineCode("DB570"))
    }

    @Test fun plannedWeekRepliesUseOneWeekPrefix() {
        assertEquals("本周日", formatRepairPlannedTime("本周", "周日"))
        assertEquals("下周三", formatRepairPlannedTime("下周", "周三"))
        assertEquals("今天夜班", formatRepairPlannedTime("今天", "夜班"))
    }

    @Test fun replyPreservesOriginalLinesAndKeepsRepeatMachinesInTheirOriginalPositions() {
        val lines = listOf("故障报备", "3夹", "BI29原描述", "", "4夹", "BI29另一条")
        val notes = listOf("", "", "没报修", "", "", "计划完成：2026-11-09 15:00")
        assertEquals("故障报备\n3夹\nBI29原描述 ｜ 没报修\n\n4夹\nBI29另一条 ｜ 计划完成：2026-11-09 15:00",
            buildRepairReply(lines, notes))
    }
}
