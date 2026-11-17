package io.github.goooseby.keye

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.github.goooseby.keye.data.*
import io.github.goooseby.keye.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Exercises real SQLite transactions and ViewModel orchestration with synthetic content only. */
class CourseManagementCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = File(filesDir, "qa/course-management.json").apply { parentFile?.mkdirs(); delete() }
        lifecycleScope.launch(Dispatchers.IO) {
            val store = LibraryStore.getInstance(application)
            val prefix = "qa-course-${newId()}"
            val course = Course(id = prefix, title = "课程管理检查")
            val other = Course(id = "$prefix-other", title = "课件移入目标")
            val checked = mutableListOf<String>()
            val release = CountDownLatch(1)
            var blocked = false
            val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val bytes = ByteArrayOutputStream().also { output ->
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.LTGRAY); it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle()
                }
            }.toByteArray()
            val source = object : ClassroomPageSource {
                override fun owns(entry: ScheduleEntry) = entry.id.startsWith(prefix)
                override fun pages(entry: ScheduleEntry): List<ClassroomPage> {
                    if (entry.id.endsWith("-retry") && attempts.merge(entry.id, 1, Int::plus) == 1) error("模拟首次下载失败")
                    if (blocked) check(release.await(15, TimeUnit.SECONDS))
                    val url = "https://example.invalid/page/${entry.id}"
                    return listOf(ClassroomPage(classroomPageKey(url), url))
                }
                override fun image(page: ClassroomPage) = bytes
            }
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(this@CourseManagementCheckActivity, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = KeyeViewModel(application) { source } as T
                })[KeyeViewModel::class.java]
            }
            fun entry(suffix: String) = ScheduleEntry("$prefix-$suffix", prefix, course.title, "2026-11-17", "course", suffix)
            suspend fun waitFor(predicate: (UiState) -> Boolean) = withTimeout(20_000) { model.state.first(predicate) }
            fun members() = store.materials().filter { it.courseId == prefix }.associate { it.id to it.deleted }
            try {
                waitFor { !it.loading }
                // Compatibility is checked against Android's real JSON implementation.
                check(Course.from(JSONObject().put("id", prefix).put("title", "旧课程")).active)
                checked += "legacy-course-json"
                store.put(course); store.put(other)
                check(model.download(listOf(entry("initial"))))
                waitFor { it.tasks.any { task -> task.type == "download" && task.payload.contains("$prefix-initial") && task.status == "done" } }
                val material = store.materialBySource(entry("initial").id)!!
                check(model.download(listOf(entry("retry"))))
                val failed = waitFor { it.tasks.any { task -> task.payload.contains("$prefix-retry") && task.status == "failed" } }
                    .tasks.first { it.payload.contains("$prefix-retry") }
                check(StudyMaterial.from(JSONObject(material.json().toString()).apply { remove("courseRemovalBatchId") }).courseRemovalBatchId == null)
                val earlier = material.copy(id = "$prefix-earlier", sourceKey = "$prefix-earlier", deleted = true)
                store.put(earlier)
                val saved = material.copy(bookmarks = setOf(1), pageNotes = mapOf(1 to "保留批注"), readAt = 200)
                store.put(saved)
                model.removeManagedCourse(course, members(), false)
                val trashed = store.courses().first { it.id == prefix }
                check(!model.state.value.courses.any { it.id == prefix } && trashed.lifecycle == "trashed")
                check(store.material(saved.id)!!.deleted && store.material(saved.id)!!.pages == saved.pages)
                check(model.download(listOf(entry("after-delete"))))
                check(model.state.value.courseAcquisition != null)
                model.resolveCourseAcquisition(false)
                waitFor { it.tasks.any { task -> task.payload.contains("$prefix-after-delete") && task.status == "done" } }
                check(store.materialBySource(entry("after-delete").id)!!.courseId == null)
                check(store.material(saved.id)!!.deleted)
                checked += "deleted-course-requires-destination-decision"
                model.retryTask(failed.id)
                check(model.state.value.courseAcquisition?.retryTaskId == failed.id)
                model.resolveCourseAcquisition(false)
                waitFor { it.tasks.any { task -> task.id == failed.id && task.status == "done" } }
                check(store.tasks().count { it.payload.contains("$prefix-retry") } == 1)
                check(store.materialBySource(entry("retry").id)!!.courseId == null)
                checked += "retry-keeps-task-identity-after-course-removal"
                model.restoreManagedCourse(prefix)
                check(store.material(saved.id) == saved)
                check(store.material(earlier.id)!!.deleted)
                checked += "delete-and-selective-restore-preserves-content"
                // Standalone restore must detach from a removed course and stay detached on group restore.
                model.removeManagedCourse(store.courses().first { it.id == prefix }, members(), false)
                model.setDeleted(saved.id, false)
                waitFor { it.materials.any { m -> m.id == saved.id && !m.deleted && m.courseId == null } }
                model.restoreManagedCourse(prefix)
                check(store.material(saved.id)!!.courseId == null)
                checked += "individual-restore-keeps-unfiled-destination"
                model.assignCourse(saved.id, prefix)
                waitFor { it.materials.any { m -> m.id == saved.id && m.courseId == prefix } }
                // A failing transaction cannot leave a partially changed course or material.
                runCatching { store.transaction { store.put(course.copy(lifecycle = "trashed")); error("rollback") } }
                check(store.courses().first { it.id == prefix }.active)
                checked += "sqlite-rollback"
                blocked = true
                check(model.download(listOf(entry("blocked"))))
                waitFor { it.tasks.any { task -> task.payload.contains("$prefix-blocked") && task.status == "running" } }
                check(runCatching { model.removeManagedCourse(course, members(), true) }.isFailure)
                release.countDown()
                waitFor { it.tasks.any { task -> task.payload.contains("$prefix-blocked") && task.status == "done" } }
                checked += "running-download-blocks-removal"
                model.removeManagedCourse(course, members(), true)
                check(store.materials().filter { it.sourceKey.startsWith(prefix) }.all { it.courseId == null })
                check(model.download(listOf(entry("after-dissolve"))))
                waitFor { it.tasks.any { task -> task.payload.contains("$prefix-after-dissolve") && task.status == "done" } }
                check(store.materialBySource(entry("after-dissolve").id)!!.courseId == null)
                check(store.courses().first { it.id == prefix }.lifecycle == "dissolved")
                checked += "dissolved-school-course-does-not-reappear"
                report.writeText(JSONObject().put("passed", true).put("checks", JSONArray(checked)).toString())
            } catch (failure: Exception) {
                report.writeText(JSONObject().put("passed", false).put("checks", JSONArray(checked)).put("error", failure.stackTraceToString()).toString())
            } finally {
                release.countDown()
                withContext(Dispatchers.Main) { viewModelStore.clear() }
                // Only this run's generated IDs are removed; existing user data is untouched.
                val materials = store.materials().filter { it.sourceKey.startsWith(prefix) }
                val tasks = store.tasks().filter { it.payload.contains(prefix) }
                store.transaction {
                    materials.forEach { store.writableDatabase.delete("materials", "id=?", arrayOf(it.id)) }
                    tasks.forEach { store.writableDatabase.delete("tasks", "id=?", arrayOf(it.id)) }
                    listOf(prefix, other.id).forEach { store.writableDatabase.delete("courses", "id=?", arrayOf(it)) }
                }
                materials.filterNot { it.id.endsWith("-earlier") }.forEach { MediaLibrary(application).removeIncomplete(it.id) }
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }
}
