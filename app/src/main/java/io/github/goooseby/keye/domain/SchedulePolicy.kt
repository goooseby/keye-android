package io.github.goooseby.keye.domain

import io.github.goooseby.keye.data.ScheduleEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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

internal val schoolZone: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")
internal fun schoolToday(): LocalDate = LocalDate.now(schoolZone)
internal fun schoolWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
internal fun scheduleIsFresh(checkedAt: Long,now: Long=System.currentTimeMillis()): Boolean = checkedAt>0 && now-checkedAt in 0..90_000
internal fun lessonStatus(status: Int?): String = when(status) {
    1 ->"直播中";2 ->"未开始";3 ->"回放生成中";5 ->"暂无回放";6 ->"回放";else ->"状态未知"
}
internal fun schoolLessonVisible(entry: ScheduleEntry,isAdmin: Boolean=false): Boolean = isAdmin || when(entry.reviewType) {
    2 ->entry.status !in setOf(3,5,6)
    3 ->entry.status !in setOf(1,2)
    else ->true
}
internal fun lessonTime(epoch: Long?): String = epoch?.let {java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(schoolZone).format(Instant.ofEpochMilli(it))} ?: "时间待定"

/** Group overlapping display blocks per day, including their minimum touch height. */
internal data class LessonPlacement(val entry: ScheduleEntry,val startMinute: Int,val endMinute: Int,val group: Int)
internal fun placeLessons(entries: List<ScheduleEntry>): List<LessonPlacement> = entries.groupBy {it.day}.values.flatMap {placeDayLessons(it)}

private fun placeDayLessons(entries: List<ScheduleEntry>): List<LessonPlacement> {
    val sorted=entries.mapNotNull {entry ->
        val start=entry.startsAt?.let {Instant.ofEpochMilli(it).atZone(schoolZone).toLocalTime().toSecondOfDay()/60} ?: return@mapNotNull null
        val end=entry.endsAt?.let {Instant.ofEpochMilli(it).atZone(schoolZone).toLocalTime().toSecondOfDay()/60} ?: return@mapNotNull null
        if(end<=start) null else Triple(entry,start,end)
    }.sortedBy {it.second}
    val result=mutableListOf<LessonPlacement>();var clusterEnd=-1;var group=-1
    for((entry,start,end) in sorted) {
        if(start>=clusterEnd) group++
        result+=LessonPlacement(entry,start,end,group)
        clusterEnd=maxOf(clusterEnd,maxOf(end,start+48))
    }
    return result
}


/** Cached metadata never expires from storage; this only decides background revalidation. */
internal fun shouldRefreshScheduleWeek(week: LocalDate,checkedAt: Long,now: Long=System.currentTimeMillis(),today: LocalDate=schoolToday()): Boolean {
    if(checkedAt<=0 || now<checkedAt) return true
    val current=schoolWeek(today)
    val interval=when {week==current ->60_000L;week.isBefore(current) ->86_400_000L;else ->21_600_000L}
    return now-checkedAt>=interval
}
