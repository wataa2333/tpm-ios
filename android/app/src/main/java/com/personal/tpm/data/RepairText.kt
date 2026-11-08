package com.personal.tpm.data

private val reportMachine = Regex(
    """^\s*([A-Z]{2}[0-9]{2}|[A-Z]-[A-Z][0-9]{2})(?![0-9])""",
    RegexOption.IGNORE_CASE,
)

fun reportMachineCode(line: String): String =
    reportMachine.find(line)?.groupValues?.get(1)?.uppercase().orEmpty()

private val compactMachine = Regex("""([A-Z])([A-Z][0-9]{2})""")

fun normalizeReportMachineCode(code: String): String {
    val upper = code.trim().uppercase()
    return compactMachine.replace(upper) { match ->
        if (match.value == upper) "${match.groupValues[1]}-${match.groupValues[2]}" else match.value
    }
}

fun repairReplyLine(line: String, annotation: String, manual: String? = null): String =
    manual ?: if (annotation.isEmpty()) line else "$line ｜ $annotation"

fun buildRepairReply(lines: List<String>, annotations: List<String>, manualLines: List<String?> = emptyList()): String =
    lines.mapIndexed { index, line ->
        repairReplyLine(line, annotations.getOrNull(index).orEmpty(), manualLines.getOrNull(index))
    }.joinToString("\n")
