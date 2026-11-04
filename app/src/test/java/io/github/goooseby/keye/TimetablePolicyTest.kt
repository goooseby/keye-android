package io.github.goooseby.keye

import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TimetablePolicyTest {
    private fun entry(id: String,day: String="2026-11-02",start: String="09:00",end: String="11:00")=ScheduleEntry(id,"course","Course",day,"course",id,
        java.time.LocalDateTime.parse("${day}T${start}:00").atZone(schoolZone).toInstant().toEpochMilli(),
        java.time.LocalDateTime.parse("${day}T${end}:00").atZone(schoolZone).toInstant().toEpochMilli())

    @Test fun weekNavigationCrossesYearAndMonthBoundaries() {
        assertEquals("2026-12-28",schoolWeek(LocalDate.parse("2027-01-01")).toString())
        assertEquals("2026-10-26",schoolWeek(LocalDate.parse("2026-11-01")).toString())
        assertEquals("2026-11-02",schoolWeek(LocalDate.parse("2026-11-04")).toString())
    }
    @Test fun platformStatusIsIndependentOfTheClockAndUnknownCodesStayUnknown() {
        assertEquals("直播中",lessonStatus(1));assertEquals("未开始",lessonStatus(2))
        assertEquals("回放生成中",lessonStatus(3));assertEquals("暂无回放",lessonStatus(5));assertEquals("回放",lessonStatus(6))
        assertEquals("状态未知",lessonStatus(null));assertEquals("状态未知",lessonStatus(99))
        assertFalse(scheduleIsFresh(0,90_000));assertFalse(scheduleIsFresh(100_000,99_999))
        assertTrue(scheduleIsFresh(100_000,189_999));assertFalse(scheduleIsFresh(100_000,190_001))
    }
    @Test fun lessonsOnDifferentDaysDoNotShareOverlapColumns() {
        val a=entry("a");val b=entry("b",start="10:00",end="12:00");val c=entry("c",day="2026-11-04")
        val positions=placeLessons(listOf(c,b,a)).associateBy {it.entry.id}
        assertEquals(2,positions.getValue("a").columns);assertEquals(2,positions.getValue("b").columns)
        assertNotEquals(positions.getValue("a").column,positions.getValue("b").column)
        assertEquals(1,positions.getValue("c").columns)
        val adjacent=placeLessons(listOf(a,entry("after",start="11:00",end="12:00")))
        assertTrue(adjacent.all {it.columns==1})
    }
    @Test fun shortCardsStayTappableWithoutCoveringEachOther() {
        val placements=placeLessons(listOf(entry("short1",start="09:00",end="09:10"),entry("short2",start="09:20",end="09:30")))
        assertTrue(placements.all {it.columns==2});assertEquals(2,placements.map {it.column}.distinct().size)
        assertTrue(placeLessons(listOf(entry("bad",end="08:00"),entry("unknown").copy(startsAt=null))).isEmpty())
    }
    @Test fun publicationVisibilityUsesPlatformRulesAndAdminException() {
        val lesson=entry("a")
        assertFalse(schoolLessonVisible(lesson.copy(status=6,reviewType=2)))
        assertFalse(schoolLessonVisible(lesson.copy(status=1,reviewType=3)))
        assertTrue(schoolLessonVisible(lesson.copy(status=1,reviewType=2)))
        assertTrue(schoolLessonVisible(lesson.copy(status=6,reviewType=3)))
        assertTrue(schoolLessonVisible(lesson.copy(status=6,reviewType=2),isAdmin=true))
        assertTrue(schoolLessonVisible(lesson))
    }
    @Test fun cachedWeeksUseDifferentRevalidationIntervalsWithoutDeletingTheirContent() {
        val today=LocalDate.parse("2026-11-04");val current=schoolWeek(today);val saved=1_000_000L
        assertFalse(shouldRefreshScheduleWeek(current,saved,saved+59_999,today))
        assertTrue(shouldRefreshScheduleWeek(current,saved,saved+60_000,today))
        assertFalse(shouldRefreshScheduleWeek(current.minusWeeks(1),saved,saved+86_399_999,today))
        assertTrue(shouldRefreshScheduleWeek(current.minusWeeks(1),saved,saved+86_400_000,today))
        assertFalse(shouldRefreshScheduleWeek(current.plusWeeks(1),saved,saved+21_599_999,today))
        assertTrue(shouldRefreshScheduleWeek(current.plusWeeks(1),saved,saved+21_600_000,today))
        assertTrue(shouldRefreshScheduleWeek(current,0,saved,today))
        assertTrue(shouldRefreshScheduleWeek(current,saved,saved-1,today))
    }

}
