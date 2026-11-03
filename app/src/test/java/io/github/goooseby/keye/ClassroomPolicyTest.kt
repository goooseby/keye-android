package io.github.goooseby.keye

import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.domain.ClassroomPage
import io.github.goooseby.keye.domain.classroomAppendTarget
import io.github.goooseby.keye.domain.classroomPageKey
import io.github.goooseby.keye.domain.classroomRetryMillis
import io.github.goooseby.keye.domain.classroomUnseenPages
import io.github.goooseby.keye.domain.pendingClassroomPages
import io.github.goooseby.keye.domain.selectExportPages
import org.junit.Assert.*
import org.junit.Test

class ClassroomPolicyTest {
    @Test fun rotatingCredentialsDoNotDownloadTheSameSlideAgain() {
        val a=classroomPageKey("https://images.example.edu/course/a.jpg?token=old&expires=1&page=2")
        val b=classroomPageKey("https://images.example.edu/course/a.jpg?page=2&expires=8&token=new")
        assertEquals(a,b)
        assertNotEquals(a,classroomPageKey("https://images.example.edu/course/a.jpg?page=3"))
        assertNotEquals(a,classroomPageKey("https://images.example.edu/course/b.jpg?page=2"))
    }
    @Test fun unchangedOrReorderedCatalogDoesNotAddDuplicatesOrDeleteCachedSlides() {
        val pages=(1..4).map {ClassroomPage(it.toString(),"url-$it")}
        assertEquals(listOf("3","4"),pendingClassroomPages(setOf("1","2"),pages+pages).map {it.key})
        assertTrue(pendingClassroomPages(setOf("1","2","3","4"),pages.reversed()).isEmpty())
        assertTrue(pendingClassroomPages(setOf("1","2"),emptyList()).isEmpty())
    }
    @Test fun newSlidesFollowOnlyWhenTheUserHasNotStartedBrowsingEarlier() {
        assertEquals(5,classroomAppendTarget(true,2,6))
        assertEquals(2,classroomAppendTarget(false,2,6))
        assertEquals(5,classroomAppendTarget(false,5,7))
        assertEquals(0,classroomAppendTarget(true,0,0))
        assertEquals(2,classroomAppendTarget(false,10,3))
        assertEquals(3,classroomUnseenPages(9,6))
        assertEquals(0,classroomUnseenPages(6,9))
    }
    @Test fun repeatedNetworkFailuresBackOffAndStayBounded() {
        assertEquals(listOf(3000L,6000L,12000L,24000L,30000L,30000L),(1..6).map {classroomRetryMillis(it,3)})
        assertEquals(10000L,classroomRetryMillis(1,10))
        assertEquals(30000L,classroomRetryMillis(100,3))
    }
    @Test fun snapshotsKeepTheAlreadyCapturedPagesAndNotes() {
        val first=StudyMaterial(sourceKey="class",title="class",day="2026-11-03",kind="images",pages=listOf(PageFile("1","p1")),pageNotes=mapOf(1 to "note"))
        val grown=first.copy(pages=first.pages+PageFile("2","p2"),pageNotes=first.pageNotes+(2 to "next note"))
        assertEquals(1,first.pages.size)
        assertEquals(mapOf(1 to "note"),first.pageNotes)
        assertEquals(2,grown.pages.size)
        assertEquals(setOf(1),selectExportPages(listOf(grown),setOf(1))[grown.id])
    }
}
