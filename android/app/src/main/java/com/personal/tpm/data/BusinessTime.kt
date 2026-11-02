package com.personal.tpm.data

import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

/** Unzoned timestamps in this factory API are China local time, independent of the phone's zone. */
val BusinessZone: ZoneId = ZoneId.of("Asia/Shanghai")
internal fun businessTimeZone(): TimeZone = TimeZone.getTimeZone(BusinessZone.id)

/** Preserve valid entered times; missing/future/reversed defaults fall back to the repair's actual timestamps. */
fun repairTimeRange(repair: Jv, now: Date = Date(), enteredStart: Jv = Jv.NULL,
    enteredEnd: Jv = Jv.NULL): Pair<String, String> {
    val end = parsedBusinessDateOrNull(enteredEnd)?.takeUnless { it.after(now) } ?: now
    val start = listOf(enteredStart, repair["signTime"], repair["startTime"], repair["createTime"])
        .firstNotNullOfOrNull { parsedBusinessDateOrNull(it)?.takeUnless { date -> date.after(end) } } ?: end
    return serverDate(start) to serverDate(end)
}
