package io.github.goooseby.keye

import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.data.TaskRecord
import io.github.goooseby.keye.data.mergeTaskBatch
import io.github.goooseby.keye.data.taskQueueOrder
import io.github.goooseby.keye.domain.pageRemovalResult
import io.github.goooseby.keye.domain.retainedPageIndices
import io.github.goooseby.keye.domain.selectExportPages
import io.github.goooseby.keye.domain.visiblePositionForPage
import io.github.goooseby.keye.export.parsePageRange
import org.junit.Assert.*
import org.junit.Test

class ReadingAndQueueTest {
    private fun material(id: String,count: Int,removed: Set<Int> = emptySet()) = StudyMaterial(id=id,sourceKey=id,title=id,day="2026-11-02",kind="pdf",pages=List(count) {PageFile("source","preview",it)},excluded=removed)

    @Test fun progressUpdatesDoNotMoveTasksOrReverseTheBatch() {
        val batch=(0 until 9).map {TaskRecord(id="task-$it",type="download",title="Course $it",createdAt=1000,batchOrder=it,updatedAt=1000)}
        val progressed=batch.mapIndexed {i,t ->t.copy(progress=i*10,status=if(i==3) "done" else "running",updatedAt=9000-i*150L)}
        assertEquals(batch.map {it.id},progressed.reversed().sortedWith(taskQueueOrder).map {it.id})
        val newer=TaskRecord(id="new",type="download",title="New batch",createdAt=2000)
        assertEquals(listOf("new")+batch.map {it.id},(progressed+newer).sortedWith(taskQueueOrder).map {it.id})
    }

    @Test fun batchPublicationCannotDuplicateRecordsAlreadySeenByLibraryRefresh() {
        val batch=(0 until 9).map {TaskRecord(id="task-$it",type="download",title="Course",createdAt=1000,batchOrder=it)}
        val merged=mergeTaskBatch(batch.take(3),batch)
        assertEquals(9,merged.size)
        assertEquals(batch.map {it.id},merged.map {it.id})
    }

    @Test fun removingTheCurrentPageChoosesNextAndRemovingLastChoosesPrevious() {
        val removed=pageRemovalResult(5,setOf(2),setOf(3))
        val visible=retainedPageIndices(5,removed)
        assertEquals(listOf(0,3,4),visible)
        assertEquals(1,visiblePositionForPage(visible,2))
        assertEquals(1,visiblePositionForPage(listOf(0,3),4))
        assertEquals(listOf(0,1,2,3,4),retainedPageIndices(5,removed-setOf(2,3)))
    }

    @Test(expected=IllegalArgumentException::class) fun cannotRemoveEveryRemainingPage() {pageRemovalResult(3,setOf(2),setOf(1,3))}
    @Test(expected=IllegalArgumentException::class) fun cannotRemoveOutOfRangePage() {pageRemovalResult(3,emptySet(),setOf(4))}

    @Test fun exportUsesOriginalNumbersAcrossDocumentsAndSkipsRemovedPages() {
        val documents=listOf(material("a",4,setOf(2)),material("b",3,setOf(1)))
        val all=selectExportPages(documents,(1..7).toSet())
        assertEquals(setOf(1,3,4),all["a"])
        assertEquals(setOf(2,3),all["b"])
        val ranged=selectExportPages(documents,parsePageRange("2，3-6,6",7))
        assertEquals(setOf(3,4),ranged["a"])
        assertEquals(setOf(2),ranged["b"])
        assertTrue(selectExportPages(documents,setOf(2,5)).values.all {it.isEmpty()})
    }

    @Test(expected=IllegalArgumentException::class) fun oversizedRangeEndIsRejected() {parsePageRange("1-9999999999999999999999",44)}
    @Test(expected=IllegalArgumentException::class) fun reverseRangeIsRejected() {parsePageRange("5-2",44)}
}
