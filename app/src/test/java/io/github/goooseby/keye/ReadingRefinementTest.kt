package io.github.goooseby.keye

import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.domain.ClassroomPage
import io.github.goooseby.keye.domain.insertClassroomPage
import io.github.goooseby.keye.domain.isInProgress
import io.github.goooseby.keye.domain.scheduleTimestamp
import io.github.goooseby.keye.domain.semesterStart
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReadingRefinementTest {
    @Test fun semesterBoundariesUseNearestMondayAcrossYears() {
        assertEquals(LocalDate.of(2026,8,31),semesterStart(LocalDate.of(2026,11,3)))
        assertEquals(LocalDate.of(2026,3,2),semesterStart(LocalDate.of(2026,3,2)))
        assertEquals(LocalDate.of(2025,9,1),semesterStart(LocalDate.of(2026,1,15)))
        assertEquals(LocalDate.of(2026,3,2),semesterStart(LocalDate.of(2026,8,30)))
    }
    @Test fun courseIsLiveOnlyWithinKnownStartAndEnd() {
        val base=ScheduleEntry("id","group","name","2026-11-03","course","sub")
        assertFalse(base.isInProgress(1000))
        val timed=base.copy(startsAt=1000,endsAt=2000)
        assertFalse(timed.isInProgress(999));assertTrue(timed.isInProgress(1000))
        assertTrue(timed.isInProgress(1999));assertFalse(timed.isInProgress(2000))
        assertFalse(timed.copy(endsAt=999).isInProgress(1000))
        assertEquals(1793580570000L,scheduleTimestamp("1793580570"))
        assertEquals(1793580570000L,scheduleTimestamp("1793580570000"))
        assertNull(scheduleTimestamp(""))
    }
    @Test fun latestFirstBackfillKeepsOrderAndMovesNotesWithTheirPage() {
        val catalog=(1..5).map {ClassroomPage("$it","https://example.edu/$it")}
        var material=StudyMaterial(sourceKey="id",title="name",day="day",kind="images",pages=emptyList())
        material=insertClassroomPage(material,PageFile("5","5",remoteKey="5"),catalog)
            .copy(pageNotes=mapOf(1 to "latest note"),bookmarks=setOf(1),excluded=setOf(1))
        for(n in 4 downTo 1) material=insertClassroomPage(material,PageFile("$n","$n",remoteKey="$n"),catalog)
        assertEquals(listOf("1","2","3","4","5"),material.pages.map {it.source})
        assertEquals(mapOf(5 to "latest note"),material.pageNotes)
        assertEquals(setOf(5),material.bookmarks);assertEquals(setOf(5),material.excluded)
        assertEquals(5,material.lastPage)
        val snapshot=material
        material=insertClassroomPage(material,PageFile("6","6",remoteKey="6"),catalog+ClassroomPage("6","url"))
        assertEquals(5,snapshot.pages.size);assertEquals(6,material.pages.size)
        assertEquals(snapshot.pageNotes,material.pageNotes)
    }
}
