package io.github.goooseby.keye

import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.domain.removeCourse
import io.github.goooseby.keye.domain.restoreCourse
import io.github.goooseby.keye.domain.restoreCourseMaterial
import io.github.goooseby.keye.domain.schoolCourseDestination
import org.junit.Assert.*
import org.junit.Test

class CourseManagementTest {
    private val course = Course(id = "school-group", title = "高等数学", term = "秋季", createdAt = 10)
    private fun material(id: String, deleted: Boolean = false) = StudyMaterial(id = id, sourceKey = id,
        title = id, day = "2026-11-17", courseId = course.id, kind = "images",
        pages = listOf(PageFile("original-$id", "preview-$id")), lastPage = 1, readAt = 100,
        bookmarks = setOf(1), pageNotes = mapOf(1 to "重点"), revision = 4, exportRevision = 4,
        exportUri = "content://export", deleted = deleted, touched = 100)

    @Test fun dissolveDetachesActiveAndPreviouslyDeletedMaterialsWithoutChangingContent() {
        val active = material("active")
        val deleted = material("deleted", true)
        val other = material("moved").copy(courseId = "another-course")
        val change = removeCourse(course, listOf(active, deleted, other), true, "batch", 200)
        assertEquals("dissolved", change.course.lifecycle)
        assertEquals(listOf(active.copy(courseId = null), deleted.copy(courseId = null)), change.materials)
        assertNull(schoolCourseDestination(change.course, course.id, false))
    }

    @Test fun restoreOnlyRestoresMembersDeletedByThisCourseOperation() {
        val active = material("active")
        val earlier = material("earlier", true)
        val change = removeCourse(course, listOf(active, earlier), false, "batch", 200)
        assertEquals("trashed", change.course.lifecycle)
        assertEquals(earlier, change.materials.last())
        val restored = restoreCourse(change.course, change.materials)
        assertTrue(restored.course.active)
        assertEquals(listOf(active), restored.materials)
    }

    @Test fun individuallyRestoredOrMovedMaterialsAreNotReassignedByGroupRestore() {
        val change = removeCourse(course, listOf(material("a"), material("b"), material("c")), false, "batch", 200)
        val a = restoreCourseMaterial(change.materials[0], listOf(change.course))
        assertNull(a.courseId)
        assertFalse(a.deleted)
        assertNull(a.courseRemovalBatchId)
        val b = change.materials[1].copy(courseId = "another-course", courseRemovalBatchId = null)
        val restored = restoreCourse(change.course, listOf(a, b, change.materials[2]))
        assertEquals(listOf(material("c")), restored.materials)
    }

    @Test fun emptyCourseRemovalDetachesPreviouslyDeletedMembers() {
        val removed = removeCourse(course, listOf(material("earlier", true)), false, "batch", 200)
        assertEquals("dissolved", removed.course.lifecycle)
        assertTrue(removed.materials.single().deleted)
        assertNull(removed.materials.single().courseId)
    }

    @Test fun restoredCourseCanBeDeletedAgainWithoutRevivingAnEarlierBatch() {
        val first = removeCourse(course, listOf(material("a"), material("b", true)), false, "first", 200)
        val back = restoreCourse(first.course, first.materials)
        val second = removeCourse(back.course, back.materials + first.materials.last(), false, "second", 300)
        assertEquals("second", second.materials.first().courseRemovalBatchId)
        assertEquals(listOf(material("a")), restoreCourse(second.course, second.materials).materials)
    }

    @Test fun schoolRoutingPreservesActiveIdentityAndRequiresDecisionForTrashedCourse() {
        assertEquals(course.id, schoolCourseDestination(null, course.id, false))
        assertEquals(course.id, schoolCourseDestination(course.copy(title = "自定义名称"), course.id, false))
        val removed = course.copy(lifecycle = "trashed")
        assertNull(schoolCourseDestination(removed, course.id, true))
        assertThrows(IllegalStateException::class.java) { schoolCourseDestination(removed, course.id, false) }
    }

    @Test fun repeatRemovalCannotOverwriteTheRestoreBatch() {
        val first = removeCourse(course, listOf(material("a")), false, "first", 200)
        assertThrows(IllegalArgumentException::class.java) { removeCourse(first.course, first.materials, false, "second", 300) }
    }
}
