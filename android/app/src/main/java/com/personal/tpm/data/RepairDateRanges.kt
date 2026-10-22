package com.personal.tpm.data

import java.time.LocalDate

fun currentMonthSpan(today: LocalDate = LocalDate.now()): DateSpan = DateSpan(today.withDayOfMonth(1), today)
