package io.github.goooseby.keye.domain

import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.StudyMaterial

data class CourseChange(val course: Course, val materials: List<StudyMaterial>)

/** Only current members change; content and reading state remain untouched. */
fun removeCourse(course: Course, materials: List<StudyMaterial>, dissolve: Boolean, batch: String, now: Long): CourseChange {
    require(course.active) { "课程已移除，请返回课程列表" }
    val members = materials.filter { it.courseId == course.id }
    val ungroup = dissolve || members.none { !it.deleted }
    return CourseChange(
        course.copy(lifecycle = if (ungroup) "dissolved" else "trashed", removedAt = now,
            removalBatchId = if (ungroup) null else batch),
        members.map {
            if (ungroup) it.copy(courseId = null, courseRemovalBatchId = null)
            else if (!it.deleted) it.copy(deleted = true, courseRemovalBatchId = batch)
            else it
        }
    )
}

fun restoreCourse(course: Course, materials: List<StudyMaterial>): CourseChange {
    require(course.lifecycle == "trashed") { "课程已恢复或不存在" }
    return CourseChange(course.copy(lifecycle = "active", removedAt = 0, removalBatchId = null),
        materials.filter { it.courseId == course.id && it.deleted && course.removalBatchId != null && it.courseRemovalBatchId == course.removalBatchId }
            .map { it.copy(deleted = false, courseRemovalBatchId = null) })
}

fun restoreCourseMaterial(material: StudyMaterial, courses: List<Course>): StudyMaterial = material.copy(
    deleted = false, courseRemovalBatchId = null,
    courseId = material.courseId?.takeIf { id -> courses.any { it.id == id && it.active } }
)

/** A removed school group remains a tombstone, so subsequent queries cannot recreate it. */
fun schoolCourseDestination(course: Course?, groupId: String, allowUnfiled: Boolean): String? = when {
    course == null || course.active -> groupId
    course.lifecycle == "dissolved" || allowUnfiled -> null
    else -> error("课程在回收站中，请先恢复课程或选择保存到未归类")
}
