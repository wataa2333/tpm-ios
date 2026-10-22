package com.personal.tpm.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseHistoryTest {
    @Test fun changesAndFootnotesAreSeparatedWithoutLosingContinuationLines() {
        assertEquals(listOf("1. 第一项\n接续说明", "2. 第二项", "待设备验收"),
            releaseNoteParagraphs("1. 第一项\n接续说明\n2. 第二项\n\n待设备验收"))
    }
    @Test fun bundledNotesRequireMatchingVersionAndActualContent() {
        assertEquals("修正列表", releaseEntry("1.59.0-notes.txt", "1.59.0 更新日志\n\n修正列表").notes)
        assertThrows(IllegalArgumentException::class.java) { releaseEntry("1.59.0-notes.txt", "1.58.0 更新日志\n旧日志") }
        assertThrows(IllegalArgumentException::class.java) { releaseEntry("1.59.0-notes.txt", "1.59.0 更新日志\n  ") }
    }
    @Test fun historySortsNumericallyRatherThanLexically() {
        val entries = listOf("1.9.0", "1.58.0", "1.10.0", "2.0.0").map { ReleaseEntry(it, "变更") }
        assertEquals(listOf("2.0.0", "1.58.0", "1.10.0", "1.9.0"), sortedReleaseHistory(entries).map { it.version })
    }
}
