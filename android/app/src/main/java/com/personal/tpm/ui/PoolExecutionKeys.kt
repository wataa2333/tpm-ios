package com.personal.tpm.ui

import com.personal.tpm.data.Jv

/** The form keeps its record order fixed. Server recordId may be a repeated placeholder (0). */
internal fun poolExecutionRecordKeys(records: List<Jv>): List<String> =
    records.mapIndexed { index, record -> "check:$index:${record["recordId"].text}" }

/** Use the first unique record in each displayed row, rather than joining non-unique server IDs. */
internal fun poolExecutionRowKeys(recordKeys: List<String>, columns: Int): List<String> =
    recordKeys.chunked(columns.coerceAtLeast(1)).map { "checks:${it.first()}" }
