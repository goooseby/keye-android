package io.github.goooseby.keye

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters

/** Most recent spring/autumn boundary, rounded to the nearest Monday (ties impossible). */
internal fun semesterStart(today: LocalDate): LocalDate =
    (today.year-1..today.year).flatMap {year ->listOf(3,9).map {month ->
        val anchor=LocalDate.of(year,month,1)
        val previous=anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if(java.time.temporal.ChronoUnit.DAYS.between(previous,anchor)<=3) previous else previous.plusWeeks(1)
    }}.filterNot {it.isAfter(today)}.maxOrNull()!!

internal fun scheduleTimestamp(value: String): Long? {
    value.toLongOrNull()?.takeIf {it>0}?.let {return if(it<100_000_000_000L) it*1000 else it}
    return runCatching {Instant.parse(value).toEpochMilli()}.getOrNull()
}

internal fun ScheduleEntry.isInProgress(now: Long): Boolean =
    startsAt!=null && endsAt!=null && endsAt>startsAt && now>=startsAt && now<endsAt
