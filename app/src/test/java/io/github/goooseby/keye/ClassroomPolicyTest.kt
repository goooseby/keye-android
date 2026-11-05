package io.github.goooseby.keye

import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.domain.insertClassroomPage
import io.github.goooseby.keye.domain.ClassroomPage
import io.github.goooseby.keye.domain.classroomAppendTarget
import io.github.goooseby.keye.domain.classroomPageKey
import io.github.goooseby.keye.domain.classroomRetryMillis
import io.github.goooseby.keye.domain.classroomUnseenPages
import io.github.goooseby.keye.domain.pendingClassroomPages
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
    @Test fun partialLiveCaptureCanBeBackfilledWithoutLosingPageBoundData() {
        val catalog=(1..6).map {ClassroomPage("$it","url-$it")}
        var partial=StudyMaterial(sourceKey="lesson",title="lesson",day="day",kind="images",
            pages=listOf(PageFile("5","5",remoteKey="5"),PageFile("6","6",remoteKey="6")),
            remotePageCount=6,pageNotes=mapOf(1 to "note on five"),bookmarks=setOf(2),excluded=setOf(1),lastPage=2)
        val missing=pendingClassroomPages(partial.pages.mapNotNull {it.remoteKey}.toSet(),catalog)
        assertEquals(listOf("1","2","3","4"),missing.map {it.key})
        for(remote in missing) partial=insertClassroomPage(partial,PageFile(remote.key,remote.key,remoteKey=remote.key),catalog)
        assertEquals((1..6).map {"$it"},partial.pages.map {it.source})
        assertEquals(mapOf(5 to "note on five"),partial.pageNotes)
        assertEquals(setOf(6),partial.bookmarks);assertEquals(setOf(5),partial.excluded)
        assertEquals(6,partial.lastPage);assertEquals(6,partial.remotePageCount)
        assertTrue(pendingClassroomPages(partial.pages.mapNotNull {it.remoteKey}.toSet(),catalog).isEmpty())
        assertEquals(listOf("7"),pendingClassroomPages(partial.pages.mapNotNull {it.remoteKey}.toSet(),catalog+ClassroomPage("7","url-7")).map {it.key})
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
