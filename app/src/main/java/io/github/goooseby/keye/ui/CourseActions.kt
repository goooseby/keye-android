package io.github.goooseby.keye.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.UiState
import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import io.github.goooseby.keye.ui.components.PillTabs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CourseRemovalDialog(course: Course, dissolve: Boolean, state: UiState, model: KeyeViewModel,
    onDismiss: () -> Unit, onTasks: () -> Unit = {}, onRemoved: () -> Unit = {}) {
    val current = state.courses.firstOrNull { it.id == course.id } ?: course
    val members = state.materials.filter { it.courseId == course.id }.associate { it.id to it.deleted }
    var expected by remember(course.id) { mutableStateOf(current) }
    var snapshot by remember(course.id) { mutableStateOf(members) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val active = snapshot.values.count { !it }
    val trashed = snapshot.values.count { it }
    val ungroup = dissolve || active == 0
    val blocker = model.courseActivity(course.id)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (active == 0) "删除空课程？" else if (dissolve) "解散「${expected.title}」？" else "删除「${expected.title}」及课件？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ungroup) {
                    if (active == 0) "仅移除「${expected.title}」课程分组。" else "$active 份课件将移到未归类，下载内容、阅读进度、书签和批注都会保留。"
                } else "课程和其中 $active 份课件将移入回收站，可在‘我的 → 回收站 → 课程’恢复。")
                if (trashed > 0) Text(if (ungroup) "另有 $trashed 份课件仍留在回收站，恢复后进入未归类。" else "原已在回收站的 $trashed 份课件不会随课程自动恢复。")
                if (ungroup) Text("以后获取这门课的新内容默认放入未归类。")
                if (blocker != null) {
                    Text(blocker, color = MaterialTheme.colorScheme.error)
                    if (blocker.contains("跟看")) TextButton(onClick = model::endClassroom) { Text("结束跟看，保留已保存内容") }
                    else TextButton(onClick = { onDismiss(); onTasks() }) { Text("查看下载管理") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && blocker == null, colors = if (ungroup) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                if (expected != current || snapshot != members) {
                    expected = current; snapshot = members; error = "课程内容已变化，请核对最新数量后再次确认"
                } else {
                    busy = true; error = null
                    scope.launch {
                        try { model.removeManagedCourse(expected, snapshot, dissolve); onDismiss(); onRemoved() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "操作失败，请重试" }
                        finally { busy = false }
                    }
                }
            }) { Text(if (active == 0) "删除空课程" else if (dissolve) "解散并保留课件" else "移入回收站") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
fun CourseTrashScreen(state: UiState, model: KeyeViewModel, materials: @Composable ((String) -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var restoring by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("课件", "课程"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.weight(1f)) {
            if (tab == 0) materials { id -> restoring = id }
            else {
                val courses = state.courseRecords.filter { it.lifecycle == "trashed" }.sortedByDescending { it.removedAt }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (courses.isEmpty()) item { EmptyState("trash", "没有已删除的课程") }
                    items(courses, key = Course::id) { course ->
                        val count = state.materials.count { it.courseId == course.id && it.deleted && course.removalBatchId != null && it.courseRemovalBatchId == course.removalBatchId }
                        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(course.removedAt))
                        Surface(onClick = { restoring = course.id }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(course.title, style = MaterialTheme.typography.titleMedium)
                                    Text("$count 份课件可随课程恢复", style = MaterialTheme.typography.bodySmall)
                                    Text("移除于 $date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AppIcon("undo", "恢复课程")
                            }
                        }
                    }
                }
            }
        }
    }
    restoring?.let { id ->
        val course = state.courseRecords.firstOrNull { it.id == id && it.lifecycle == "trashed" }
        if (course != null) CourseRestoreDialog(course, state, model, { restoring = null })
        else LaunchedEffect(id) { restoring = null }
    }
}

@Composable
private fun CourseRestoreDialog(course: Course, state: UiState, model: KeyeViewModel, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val count = state.materials.count { it.deleted && it.courseId == course.id && course.removalBatchId != null && it.courseRemovalBatchId == course.removalBatchId }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("恢复「${course.title}」？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("恢复课程及本次随课程移除的 $count 份课件。此前单独删除的内容仍留在回收站。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { model.restoreManagedCourse(course.id); onDismiss() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "恢复失败，请重试" }
                finally { busy = false }
            }
        }) { Text("恢复课程及课件") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
fun CourseAcquisitionDialog(state: UiState, model: KeyeViewModel, onCancel: () -> Unit, onFollow: (ScheduleEntry, Boolean) -> Unit) {
    val request = state.courseAcquisition ?: return
    val ids = request.entries.map { it.groupId }.toSet()
    val courses = state.courseRecords.filter { it.id in ids && it.lifecycle == "trashed" }
    val count = state.materials.count { material -> material.deleted && courses.any {
        it.id == material.courseId && it.removalBatchId != null && it.removalBatchId == material.courseRemovalBatchId
    } }
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun proceed(restore: Boolean) {
        busy = true; error = null
        scope.launch {
            try { model.resolveCourseAcquisition(restore)?.let { onFollow(it, !restore) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("课程已在回收站中") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(courses.joinToString("、") { "「${it.title}」" })
            Text("可以先恢复这 ${courses.size} 门课程及随课程移除的 $count 份课件，再继续获取；也可以将新内容保存到未归类，保留回收站中的原有内容。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { proceed(false) }) { Text("保存到未归类") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { proceed(true) }) { Text("恢复课程并继续") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("取消") } })
}
