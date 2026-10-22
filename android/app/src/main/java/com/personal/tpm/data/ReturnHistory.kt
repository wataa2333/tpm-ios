package com.personal.tpm.data

/** Only completed return actions and their actual assignees; candidate users are not actors. */
fun latestReturnActors(history: List<Jv>): List<String> {
    val returned = history.filter { node ->
        node["activityType"].text == "userTask" && !node["endTime"].isNull &&
            (node["condition"].text.contains("退单") || node["condition"].text.contains("返单"))
    }.maxByOrNull { node ->
        if (!node["sortTime"].isNull) node["sortTime"].number.toLong()
        else parsedDate(node["endTime"]).time
    } ?: return emptyList()
    return splitUserNames(returned["assignee"]).ifEmpty { splitUserNames(returned["assignees"]) }
        .filter { it.isNotBlank() && !it.contains("\${") && !it.contains("#{") }.distinct()
}
