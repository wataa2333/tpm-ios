package com.personal.tpm.data

data class ReleaseEntry(val version: String, val notes: String)

/** Preserve the wording of archived notes while separating numbered changes and footnotes. */
fun releaseNoteParagraphs(notes: String): List<String> = notes.trim()
    .split(Regex("\\n(?=\\d+\\.\\s)|\\n\\s*\\n"))
    .map { it.trim() }.filter { it.isNotEmpty() }

fun releaseEntry(filename: String, text: String): ReleaseEntry {
    val match = Regex("(\\d+\\.\\d+\\.\\d+)-notes\\.txt").matchEntire(filename)
        ?: error("更新日志文件名无效")
    val version = match.groupValues[1]
    val lines = text.trim().lines()
    require(lines.firstOrNull() == "$version 更新日志") { "$version 更新日志标题不符" }
    val notes = lines.drop(1).joinToString("\n").trim()
    require(notes.isNotBlank()) { "$version 更新日志内容为空" }
    return ReleaseEntry(version, notes)
}

fun sortedReleaseHistory(entries: List<ReleaseEntry>): List<ReleaseEntry> = entries.sortedWith(
    compareByDescending<ReleaseEntry> { it.version.split('.')[0].toInt() }
        .thenByDescending { it.version.split('.')[1].toInt() }
        .thenByDescending { it.version.split('.')[2].toInt() }
)
