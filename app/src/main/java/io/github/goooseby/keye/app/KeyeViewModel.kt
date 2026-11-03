package io.github.goooseby.keye.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.goooseby.keye.data.local.LibraryStore
import io.github.goooseby.keye.data.media.MediaLibrary
import io.github.goooseby.keye.data.media.renderReadingPage
import io.github.goooseby.keye.data.media.withPageNote
import io.github.goooseby.keye.data.model.AppSettings
import io.github.goooseby.keye.data.model.Course
import io.github.goooseby.keye.data.model.PageFile
import io.github.goooseby.keye.data.model.ScheduleEntry
import io.github.goooseby.keye.data.model.StudyMaterial
import io.github.goooseby.keye.data.model.TaskRecord
import io.github.goooseby.keye.data.model.mergeTaskBatch
import io.github.goooseby.keye.data.model.newId
import io.github.goooseby.keye.data.model.strings
import io.github.goooseby.keye.data.model.taskQueueOrder
import io.github.goooseby.keye.data.model.textOrNull
import io.github.goooseby.keye.data.school.SchoolApi
import io.github.goooseby.keye.data.school.SchoolAuth
import io.github.goooseby.keye.data.school.SchoolConnection
import io.github.goooseby.keye.data.school.SchoolRateLimited
import io.github.goooseby.keye.data.school.SchoolSessionExpired
import io.github.goooseby.keye.data.school.hasSchoolLoginCookie
import io.github.goooseby.keye.domain.classroom.ClassroomPage
import io.github.goooseby.keye.domain.classroom.ClassroomPageSource
import io.github.goooseby.keye.domain.classroom.ClassroomSession
import io.github.goooseby.keye.domain.classroom.ClassroomStage
import io.github.goooseby.keye.domain.classroom.classroomPageKey
import io.github.goooseby.keye.domain.classroom.classroomRetryMillis
import io.github.goooseby.keye.domain.classroom.insertClassroomPage
import io.github.goooseby.keye.domain.classroom.pendingClassroomPages
import io.github.goooseby.keye.domain.reading.pageRemovalResult
import io.github.goooseby.keye.export.ExportAction
import io.github.goooseby.keye.export.ExportRequest
import io.github.goooseby.keye.export.ExportResult
import io.github.goooseby.keye.export.ExportState
import io.github.goooseby.keye.export.ExportStorage
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UiState(
    val exportState: ExportState? = null,
    val classroom: ClassroomSession? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    val courses: List<Course> = emptyList(),
    val materials: List<StudyMaterial> = emptyList(),
    val tasks: List<TaskRecord> = emptyList(),
    val schedule: List<ScheduleEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val loggedIn: Boolean = false,
    val schoolConnection: SchoolConnection = SchoolConnection.SignedOut,
    val busy: Boolean = false,
    val notice: String? = null,
    val startDate: String = LocalDate.now().minusDays(14).toString(),
    val endDate: String = LocalDate.now().toString()
)

class KeyeViewModel @JvmOverloads constructor(application: Application,private val classroomSourceFactory: (() -> ClassroomPageSource?)?=null) : AndroidViewModel(application) {
    private val store = LibraryStore.getInstance(application)
    private val media = MediaLibrary(application)
    private val school = SchoolApi()
    @Volatile private var auth: SchoolAuth? = null
    private var sessionJob: Job? = null
    private val sessionSequence=java.util.concurrent.atomic.AtomicLong()
    private var lastSessionCheck=0L
    @Volatile private var rejectedCookie:String?=null
    private var scanJob: Job? = null
    private val scanSequence = java.util.concurrent.atomic.AtomicLong()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val downloadSlots = Semaphore(2)
    private val importSlots = Semaphore(1)
    private val queuedSources = ConcurrentHashMap.newKeySet<String>()
    private val materialMutex = Mutex()
    private val classroomRunLock=Mutex()
    private var classroomJob: Job?=null
    private val classroomGeneration=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var classroomForeground=false
    private val classroomWake=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ExportStorage(application).cleanExpiredShares()
                val interrupted = store.tasks().map { task ->
                    if (task.status in setOf("queued", "running")) {
                        task.copy(status = "failed", message = "上次退出时任务中断，请重试").also(store::put)
                    } else task
                }
                val courses = store.courses()
                val materials = store.materials()
                val settings = store.settings()
                mutableState.update { it.copy(loading=false,courses=courses,materials=materials,tasks=interrupted,settings=settings) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(loading=false,loadError="请重新启动应用后重试") }
            }
        }
    }

    private suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        mutableState.update {
            it.copy(courses = store.courses(), materials = store.materials(), tasks = store.tasks())
        }
    }

    private suspend fun editMaterial(id: String, transform: (StudyMaterial) -> StudyMaterial) {
        materialMutex.lock()
        try {
            val current = store.material(id) ?: error("课件不存在")
            val changed = transform(current)
            if (changed != current) {
                store.put(changed)
                refreshLibrary()
            }
        } finally {
            materialMutex.unlock()
        }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice(error.message ?: "操作失败")
            }
        }
    }

    fun notice(message: String?) {
        mutableState.update { it.copy(notice = message) }
    }

    suspend fun login(cookie: String,silent: Boolean=false): Boolean {
        val credentials=SchoolApi.authFromCookies(cookie)
        if(credentials==null) {
            if(!silent) notice("还没有识别到有效登录状态，请在学校页面完成登录")
            return false
        }
        sessionJob?.cancel()
        val sequence=sessionSequence.incrementAndGet()
        mutableState.update {it.copy(schoolConnection=SchoolConnection.Checking)}
        return try {
            withContext(Dispatchers.IO) {school.verifySession(credentials,state.value.settings.timeoutSeconds.coerceAtMost(15))}
            if(sequence!=sessionSequence.get()) return false
            auth=credentials;rejectedCookie=null
            lastSessionCheck=android.os.SystemClock.elapsedRealtime()
            android.webkit.CookieManager.getInstance().flush()
            mutableState.update {it.copy(loggedIn=true,schoolConnection=SchoolConnection.Connected,notice=if(silent) null else "已连接学校课堂平台")}
            true
        } catch(error: CancellationException) {throw error}
        catch(error: Exception) {
            if(sequence==sessionSequence.get()) {
                if(error is SchoolSessionExpired) {
                    rejectedCookie=credentials.cookie;auth=null
                    mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.Expired,schedule=emptyList(),busy=false)}
                } else mutableState.update {it.copy(schoolConnection=SchoolConnection.Unverified)}
                if(!silent) notice(error.message ?: "暂时无法验证登录，请稍后重试")
            }
            false
        }
    }

    fun checkSchoolSession(cookie: String?,force: Boolean=false) {
        if(sessionJob?.isActive==true) return
        val credentials=SchoolApi.authFromCookies(cookie)
        if(credentials==null) {
            if(auth!=null || hasSchoolLoginCookie(cookie)) {
                auth=null
                mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.Expired,schedule=emptyList(),busy=false)}
            }
            return
        }
        if(credentials.cookie==rejectedCookie) return
        if(!force && credentials==auth && android.os.SystemClock.elapsedRealtime()-lastSessionCheck<60_000) return
        val sequence=sessionSequence.incrementAndGet()
        auth=credentials
        lastSessionCheck=android.os.SystemClock.elapsedRealtime()
        mutableState.update {it.copy(loggedIn=true,schoolConnection=
            if(it.schoolConnection in setOf(SchoolConnection.Connected,SchoolConnection.Unverified)) it.schoolConnection else SchoolConnection.Checking)}
        sessionJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {school.verifySession(credentials,state.value.settings.timeoutSeconds.coerceAtMost(15))}
                if(sequence==sessionSequence.get() && auth==credentials) mutableState.update {it.copy(schoolConnection=SchoolConnection.Connected)}
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {
                if(sequence==sessionSequence.get() && auth==credentials) {
                    if(error is SchoolSessionExpired) expireSchoolSession(credentials)
                    else mutableState.update {it.copy(schoolConnection=SchoolConnection.Unverified)}
                }
            }
        }
    }

    private fun expireSchoolSession(credentials: SchoolAuth) {
        if(auth!=credentials) return
        sessionSequence.incrementAndGet()
        rejectedCookie=credentials.cookie;auth=null
        mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.Expired,busy=false,schedule=emptyList(),notice="学校登录已失效，请重新登录")}
        // CookieManager is intentionally untouched: only an explicit sign-out
        // removes cookies, so the school login page may still reuse its SSO.
    }

    fun logout() {
        sessionSequence.incrementAndGet();sessionJob?.cancel()
        scanSequence.incrementAndGet();scanJob?.cancel()
        state.value.tasks.filter {it.type=="download"}.forEach {jobs[it.id]?.cancel()}
        endClassroom()
        auth=null;rejectedCookie=null
        mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.SignedOut,busy=false,schedule=emptyList(),notice="已退出学校登录")}
    }

    fun scan(startText: String, endText: String) {
        val sequence = scanSequence.incrementAndGet()
        scanJob?.cancel()
        val credentials=auth ?: run {notice("请先登录学校平台");return}
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val start = runCatching { LocalDate.parse(startText) }.getOrElse { error("开始日期无效") }
                val end = runCatching { LocalDate.parse(endText) }.getOrElse { error("结束日期无效") }
                require(!end.isBefore(start)) { "开始日期不能晚于结束日期" }
                mutableState.update { it.copy(busy = true, startDate = startText, endDate = endText, schedule = emptyList()) }
                val entries = school.scan(credentials, start, end, state.value.settings.timeoutSeconds)
                coroutineContext.ensureActive()
                if (sequence != scanSequence.get() || auth != credentials) return@launch
                val known = store.courses().map(Course::id).toSet()
                entries.distinctBy(ScheduleEntry::groupId).filter { it.groupId !in known }
                    .forEach { store.put(Course(id = it.groupId, title = it.title)) }
                refreshLibrary()
                mutableState.update { it.copy(schedule = entries, schoolConnection=SchoolConnection.Connected, notice = "找到 ${entries.size} 节课") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sequence == scanSequence.get()) {
                    if(error is SchoolSessionExpired) expireSchoolSession(credentials)
                    else notice(error.message ?: "查询失败")
                }
            } finally {
                if (sequence == scanSequence.get()) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun setClassroomForeground(value: Boolean) {
        classroomForeground=value
        if(value) classroomWake.trySend(Unit)
        else mutableState.update {state ->state.copy(classroom=state.classroom?.let {
            if(it.stage in setOf(ClassroomStage.Ended,ClassroomStage.NeedsLogin)) it
            else it.copy(stage=ClassroomStage.Paused,fetching=false,message="已暂停同步，页面已保存")
        })}
    }
    @Volatile private var classroomManuallyPaused=false
    fun pauseClassroom() {
        classroomManuallyPaused=true
        mutableState.update {it.copy(classroom=it.classroom?.copy(stage=ClassroomStage.Paused,message="已暂停同步",fetching=false))}
        classroomWake.trySend(Unit)
    }
    fun resumeClassroom() {
        classroomManuallyPaused=false
        val session=state.value.classroom ?: return
        if(classroomJob?.isActive!=true) startClassroom(session.entry)
        else classroomWake.trySend(Unit)
    }
    fun refreshClassroom() {if(state.value.classroom?.fetching!=true) classroomWake.trySend(Unit)}
    fun classroomInterval(seconds: Int) {
        if(seconds !in setOf(3,5,10)) return
        getApplication<Application>().getSharedPreferences("classroom",0).edit().putInt("interval",seconds).apply()
        mutableState.update {it.copy(classroom=it.classroom?.copy(intervalSeconds=seconds))}
        classroomWake.trySend(Unit)
    }
    fun endClassroom() {
        classroomGeneration.incrementAndGet();classroomJob?.cancel()
        mutableState.update {it.copy(classroom=it.classroom?.copy(stage=ClassroomStage.Ended,fetching=false,message="跟看已结束，课件已保留"))}
    }
    fun startClassroom(entry: ScheduleEntry) {
        val previous=state.value.classroom
        if(previous?.entry?.id==entry.id && classroomJob?.isActive==true) {resumeClassroom();return}
        if(entry.id in queuedSources) {notice("此课次正在下载，请完成或取消下载后再跟看");return}
        val interval=getApplication<Application>().getSharedPreferences("classroom",0).getInt("interval",3).takeIf {it in setOf(3,5,10)} ?: 3
        val credentials=auth
        val source=classroomSourceFactory?.invoke() ?: credentials?.let {captured ->object: ClassroomPageSource {
            override val credentials=captured
            override fun owns(entry: ScheduleEntry)=SchoolApi.ownsEntry(captured,entry)
            private fun checkAccount() {if(auth?.jwt!=captured.jwt || auth?.userId!=captured.userId) throw SchoolSessionExpired()}
            override fun pages(entry: ScheduleEntry): List<ClassroomPage> {checkAccount();return school.imagePages(captured,entry,state.value.settings.timeoutSeconds.coerceAtMost(12))}
            override fun image(page: ClassroomPage): ByteArray {checkAccount();return school.image(captured,page.url,state.value.settings.timeoutSeconds.coerceAtMost(12))}
        }}
        val generation=classroomGeneration.incrementAndGet()
        classroomJob?.cancel();classroomManuallyPaused=false
        mutableState.update {it.copy(classroom=ClassroomSession(entry,previous?.takeIf {it.entry.id==entry.id}?.materialId ?: state.value.materials.firstOrNull {it.sourceKey==entry.id && !it.deleted}?.id,
            stage=if(source==null) ClassroomStage.NeedsLogin else ClassroomStage.Connecting,intervalSeconds=interval,
            message=if(source==null) "请登录学校平台后继续，已有页面仍可阅读" else "正在连接课堂"))}
        if(source==null) return
        if(!source.owns(entry)) {
            mutableState.update {it.copy(classroom=it.classroom?.copy(stage=ClassroomStage.NeedsLogin,message="请使用查询此课次的学校账号登录"))};return
        }
        classroomJob=viewModelScope.launch(Dispatchers.IO) {
            // A cancelled HTTP call can finish late. This lock keeps replacement
            // sessions serial too; generation guards reject late writes.
            classroomRunLock.withLock {
                fun publish(transform: (ClassroomSession) -> ClassroomSession) {
                    mutableState.update {state ->if(classroomGeneration.get()==generation) state.copy(classroom=state.classroom?.let(transform)) else state}
                }
                try {
                    state.first {!it.loading}
                    coroutineContext.ensureActive()
                    val initial=materialMutex.withLock {
                        val existing=store.materialBySource(entry.id)
                        check(existing?.deleted!=true) {"该课件在回收站中，请先恢复后跟看"}
                        check(existing==null || existing.kind=="images") {"此课件类型不支持课堂跟看"}
                        val material=(existing ?: StudyMaterial(sourceKey=entry.id,title=entry.title,day=entry.day,courseId=entry.groupId,kind="images",pages=emptyList())).copy(schoolEntry=entry)
                        if(store.courses().none {it.id==entry.groupId}) store.put(Course(id=entry.groupId,title=entry.title))
                        store.put(material);refreshLibrary();material
                    }
                    publish {it.copy(materialId=initial.id)}
                    // Old downloads have no remote keys. Match their original
                    // bytes once instead of guessing URL identity from position.
                    val legacyHashes=initial.pages.mapIndexedNotNull {index,page ->if(page.remoteKey!=null) null else runCatching {
                        val digest=java.security.MessageDigest.getInstance("SHA-256")
                        media.file(page.source).inputStream().use {input ->val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
                        page.source to digest.digest().toList()
                    }.getOrNull()}.toMap()
                    var failures=0
                    while(kotlinx.coroutines.currentCoroutineContext().isActive && classroomGeneration.get()==generation) {
                        if(!classroomForeground || classroomManuallyPaused) {
                            publish {it.copy(stage=ClassroomStage.Paused,fetching=false,message=if(classroomManuallyPaused) "已暂停同步" else "已暂停同步，页面已保存")}
                            classroomWake.receive();continue
                        }
                        while(classroomWake.tryReceive().isSuccess) { /* conflate pending controls */ }
                        var waitMillis=(state.value.classroom?.intervalSeconds ?: 3)*1000L
                        try {
                            publish {it.copy(stage=if(it.checkedAt==0L) ClassroomStage.Connecting else ClassroomStage.Connected,fetching=true,message=if(it.checkedAt==0L) "正在查找课件页面" else it.message)}
                            val before=store.material(initial.id)
                            if(before==null || before.deleted) {publish {it.copy(stage=ClassroomStage.Ended,fetching=false,message="课件已移入回收站，请恢复后重新跟看")};break}
                            val fetchStarted=android.os.SystemClock.elapsedRealtime()
                            val catalog=source.pages(entry)
                            coroutineContext.ensureActive()
                            if(classroomGeneration.get()!=generation) break
                            val current=store.material(initial.id) ?: error("课件不存在")
                            check(!current.deleted) {"课件已移入回收站，请恢复后重新跟看"}
                            val pending=pendingClassroomPages(current.pages.mapNotNull {it.remoteKey}.toSet(),catalog).asReversed()
                            publish {it.copy(remoteCount=catalog.size)}
                            for((position,remote) in pending.take(3).withIndex()) {
                                coroutineContext.ensureActive()
                                if(!classroomForeground || classroomManuallyPaused) break
                                publish {it.copy(message="正在保存新页面 ${position+1} / ${pending.size}")}
                                val bytes=source.image(remote)
                                coroutineContext.ensureActive()
                                if(classroomGeneration.get()!=generation) break
                                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toList()
                                var created:PageFile?=null
                                var committed=false
                                try {
                                    materialMutex.withLock {
                                        coroutineContext.ensureActive()
                                        check(classroomGeneration.get()==generation)
                                        val latest=store.material(initial.id) ?: error("课件不存在")
                                        check(!latest.deleted) {"课件已移入回收站"}
                                        if(latest.pages.any {it.remoteKey==remote.key}) return@withLock
                                        val legacy=legacyHashes.entries.firstOrNull {(source,oldHash) ->oldHash==hash && latest.pages.any {it.source==source && it.remoteKey==null}}?.key
                                        val changed=if(legacy!=null) latest.copy(pages=latest.pages.map {page ->if(page.source==legacy) page.copy(remoteKey=remote.key) else page})
                                            else {
                                                created=media.classroomImage(latest.id,remote.key,bytes)
                                                insertClassroomPage(latest,created!!,catalog)
                                            }
                                        coroutineContext.ensureActive()
                                        store.put(changed);committed=true
                                        mutableState.update {state ->state.copy(materials=state.materials.filterNot {it.id==changed.id}+changed)}
                                    }
                                } finally {if(!committed) created?.let(media::discardClassroomPage)}
                            }
                            if(pending.size>3) waitMillis=(waitMillis-(android.os.SystemClock.elapsedRealtime()-fetchStarted)).coerceAtLeast(1L)
                            failures=0
                            publish {it.copy(stage=if(!classroomForeground||classroomManuallyPaused) ClassroomStage.Paused else if(catalog.isEmpty()) ClassroomStage.Waiting else ClassroomStage.Connected,
                                fetching=false,checkedAt=System.currentTimeMillis(),retrySeconds=0,
                                message=if(!classroomForeground||classroomManuallyPaused) "已暂停同步，页面已保存" else if(catalog.isEmpty()) "等待老师上传课件" else "已同步 · 每 ${it.intervalSeconds} 秒检查")}
                        } catch(error: CancellationException) {throw error}
                        catch(error: SchoolSessionExpired) {
                            source.credentials?.let {expireSchoolSession(it)}
                            publish {it.copy(stage=ClassroomStage.NeedsLogin,fetching=false,message="学校登录已失效，登录后可继续；已有页面已保存")};break
                        } catch(error: Exception) {
                            failures++
                            waitMillis=maxOf(classroomRetryMillis(failures,state.value.classroom?.intervalSeconds ?: 3),if(error is SchoolRateLimited) error.retryAfterSeconds*1000L else 0L)
                            publish {it.copy(stage=ClassroomStage.Retrying,fetching=false,retrySeconds=(waitMillis/1000).toInt(),message=error.message ?: "暂时无法同步，已保留页面")}
                        }
                        kotlinx.coroutines.withTimeoutOrNull(waitMillis) {classroomWake.receive()}
                    }
                } catch(error: CancellationException) {throw error}
                catch(error: Exception) {publish {it.copy(stage=ClassroomStage.Ended,fetching=false,message=error.message ?: "无法开始跟看")}}
            }
        }
    }
    suspend fun savePageNote(id: String,page: Int,text: String,source: String?=null) = withContext(Dispatchers.IO) {
        editMaterial(id) {current ->
            val target=if(source==null) page else current.pages.indexOfFirst {it.source==source}+1
            require(target in 1..current.pages.size) {"页码无效"}
            val notes=if(text.isBlank()) current.pageNotes-target else current.pageNotes+(target to text.trim().take(2000))
            current.copy(pageNotes=notes,revision=current.revision+if(notes==current.pageNotes) 0 else 1)
        }
    }

    fun createCourse(title: String, term: String = "") = action {
        require(title.isNotBlank()) { "请输入课程名称" }
        store.put(Course(title = title.trim().take(120), term = term.trim().take(80)))
        refreshLibrary()
    }

    fun renameCourse(courseId: String, title: String, term: String) = action {
        require(title.isNotBlank()) { "请输入课程名称" }
        val course = store.courses().firstOrNull { it.id == courseId } ?: error("课程不存在")
        store.put(course.copy(title = title.trim().take(120), term = term.trim().take(80)))
        refreshLibrary()
    }

    fun updateMaterial(id: String, title: String? = null, note: String? = null, courseId: String? = null) = action {
        editMaterial(id) { current ->
            current.copy(
                title = title?.trim()?.take(120)?.ifBlank { current.title } ?: current.title,
                note = note?.trim()?.take(2000) ?: current.note,
                courseId = courseId ?: current.courseId,
                touched = System.currentTimeMillis()
            )
        }
    }

    fun assignCourse(id: String, courseId: String?) = action {
        if (courseId != null && store.courses().none { it.id == courseId }) error("课程不存在")
        editMaterial(id) { it.copy(courseId = courseId, touched = System.currentTimeMillis()) }
    }

    fun assignCourses(ids: List<String>, courseId: String?) = action {
        if (courseId != null && store.courses().none { it.id == courseId }) error("课程不存在")
        materialMutex.lock()
        try {
            ids.distinct().forEach { id ->
                val current = store.material(id) ?: return@forEach
                if (!current.deleted && current.courseId != courseId) {
                    store.put(current.copy(courseId = courseId, touched = System.currentTimeMillis()))
                }
            }
            refreshLibrary()
        } finally {
            materialMutex.unlock()
        }
    }

    fun deleteMaterials(ids: List<String>) = action {
        materialMutex.lock()
        try {
            ids.distinct().forEach { id ->
                val current = store.material(id) ?: return@forEach
                if (!current.deleted) {
                    store.put(current.copy(deleted = true, touched = System.currentTimeMillis()))
                }
            }
            refreshLibrary()
        } finally {
            materialMutex.unlock()
        }
    }

    fun setDeleted(id: String, deleted: Boolean) = action {
        editMaterial(id) { it.copy(deleted = deleted, touched = System.currentTimeMillis()) }
    }

    fun toggleExcluded(id: String, page: Int) = action {
        editMaterial(id) { current ->
            require(page in 1..current.pages.size) { "页码无效" }
            val selected = current.excluded.toMutableSet()
            if (!selected.add(page)) selected.remove(page)
            current.copy(excluded = selected, revision = current.revision + 1,
                touched = System.currentTimeMillis())
        }
    }

    fun setExcluded(id: String, excluded: Set<Int>) = action {
        editMaterial(id) { current ->
            require(excluded.all { it in 1..current.pages.size }) { "页面选择无效" }
            if (excluded == current.excluded) current else current.copy(
                excluded = excluded, revision = current.revision + 1,
                touched = System.currentTimeMillis())
        }
    }

    fun removePages(id: String,pages: Set<Int>) = action {
        editMaterial(id) {current ->
            val removed=pageRemovalResult(current.pages.size,current.excluded,pages)
            current.copy(excluded=removed,revision=current.revision+1)
        }
    }

    fun restorePages(id: String,pages: Set<Int>) = action {
        editMaterial(id) {current ->current.copy(excluded=current.excluded-pages,revision=current.revision+1)}
    }

    fun markReviewed(id: String) = action {
        editMaterial(id) { it.copy(reviewRevision = it.revision, touched = System.currentTimeMillis()) }
    }

    fun lastPage(id: String, page: Int, offset: Int = 0, fraction: Float=0f,source: String?=null) = action {
        materialMutex.withLock {
            val current = store.material(id) ?: return@withLock
            val target=if(source==null) page else current.pages.indexOfFirst {it.source==source}+1
            if(target<1) return@withLock
            val updated = current.copy(lastPage=target.coerceIn(1,current.pages.size.coerceAtLeast(1)), readOffset=offset.coerceAtLeast(0), readFraction=fraction.coerceIn(0f,.999f), readAt=System.currentTimeMillis())
            store.put(updated)
            mutableState.update { state -> state.copy(materials=state.materials.map { if(it.id==id) updated else it }) }
        }
    }

    fun bookmark(id: String, page: Int,source: String?=null) = action {
        editMaterial(id) { current ->
            val target=if(source==null) page else current.pages.indexOfFirst {it.source==source}+1
            if(target<1) current else current.copy(bookmarks=if(target in current.bookmarks) current.bookmarks-target else current.bookmarks+target)
        }
    }

    private fun putTask(task: TaskRecord) {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        store.put(updated)
        mutableState.update { current ->
            current.copy(tasks = (current.tasks.filterNot { it.id == updated.id } + updated)
                .sortedWith(taskQueueOrder))
        }
    }

    fun download(entries: List<ScheduleEntry>) {
        val credentials=auth ?: run {notice("请先登录学校平台");return}
        val accepted=entries.distinctBy(ScheduleEntry::id).filter {entry ->
            if(state.value.classroom?.let {it.entry.id==entry.id && it.stage!=ClassroomStage.Ended}==true) {notice("该课次正在跟看，无需重复下载");false}
            else if(!SchoolApi.ownsEntry(credentials,entry)) {notice("请使用查询这些课次的学校账号登录");false}
            else queuedSources.add(entry.id)
        }
        if(accepted.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val batchTime=System.currentTimeMillis()
            val tasks=accepted.mapIndexed {index,entry ->TaskRecord(type="download",title=entry.title+" · "+entry.day,payload=entry.json().toString(),createdAt=batchTime,batchOrder=index,message="等待下载")}
            try {
                // Publish the complete batch before any worker can publish progress.
                store.putTasks(tasks)
                mutableState.update {it.copy(tasks=mergeTaskBatch(it.tasks,tasks))}
                val pending=tasks.zip(accepted).map {(task,entry) ->
                    val job=viewModelScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                        try {
                            downloadSlots.withPermit {
                                if(auth!=credentials) throw CancellationException("学校账号已断开")
                                downloadTask(task,entry,credentials)
                            }
                        } catch(_: CancellationException) {putTask(task.copy(status="cancelled",message="已取消"))}
                        finally {queuedSources.remove(entry.id);jobs.remove(task.id)}
                    }
                    jobs[task.id]=job
                    job
                }
                pending.forEach {it.start()}
            } catch(error: Exception) {
                accepted.forEach {queuedSources.remove(it.id)}
                if(error is CancellationException) throw error
                notice(error.message ?: "无法添加下载任务")
            }
        }
    }

    private suspend fun downloadTask(task: TaskRecord, entry: ScheduleEntry, credentials: SchoolAuth) {
        val existing = store.materialBySource(entry.id)
        if (existing != null && (existing.pages.isNotEmpty() || existing.deleted)) {
            putTask(task.copy(status = "done", progress = 100,
                message = if (existing.deleted) "课件位于回收站" else "资料库中已有此课件",
                materialId = existing.id))
            return
        }
        var savedMaterial: StudyMaterial? = null
        try {
            putTask(task.copy(status = "running", message = "正在读取课件列表"))
            val settings = state.value.settings
            val urls = school.imageUrls(credentials, entry, settings.timeoutSeconds)
            val pages = mutableListOf<PageFile>()
            urls.forEachIndexed { index, url ->
                coroutineContext.ensureActive()
                if(auth!=credentials) throw CancellationException("学校账号已断开")
                var pageFile: PageFile? = null
                var failure: Exception? = null
                for (attempt in 1..settings.retryCount) {
                    try {
                        pageFile = media.downloadedImage(
                            task.id, index, school.image(credentials, url, settings.timeoutSeconds)
                        ).copy(remoteKey=classroomPageKey(url))
                        coroutineContext.ensureActive()
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if(error is SchoolSessionExpired) throw error
                        failure = error
                        if (attempt < settings.retryCount) kotlinx.coroutines.delay(300L * attempt)
                    }
                }
                pages += pageFile ?: throw failure ?: IllegalStateException("第 ${index + 1} 页下载失败")
                putTask(task.copy(status = "running",
                    progress = ((index + 1) * 100 / urls.size),
                    message = "已下载 ${index + 1} / ${urls.size} 页"))
                if (settings.requestGapMillis > 0) kotlinx.coroutines.delay(settings.requestGapMillis)
            }
            val material = existing?.copy(pages=pages,schoolEntry=entry,revision=existing.revision+1) ?: StudyMaterial(
                id = task.id, sourceKey = entry.id, title = entry.title,
                day = entry.day, courseId = entry.groupId, kind = "images", pages = pages,schoolEntry=entry
            )
            store.put(material)
            savedMaterial = material
            putTask(task.copy(status = "done", progress = 100, message = "下载完成", materialId = material.id))
            refreshLibrary()
        } catch (error: CancellationException) {
            if (savedMaterial == null) media.removeIncomplete(task.id)
            putTask(if (savedMaterial != null) task.copy(status = "done", progress = 100,
                message = "下载完成", materialId = savedMaterial.id)
            else task.copy(status = "cancelled", message = "已取消"))
        } catch (error: Exception) {
            if(error is SchoolSessionExpired) expireSchoolSession(credentials)
            if (savedMaterial == null) media.removeIncomplete(task.id)
            putTask(if (savedMaterial != null) task.copy(status = "done", progress = 100,
                message = "下载完成", materialId = savedMaterial.id)
            else task.copy(status = "failed", message = error.message ?: "下载失败"))
        }
    }

    fun cancelTask(id: String) {
        jobs[id]?.cancel()
    }

    fun retryTask(id: String) {
        val task = state.value.tasks.firstOrNull { it.id == id } ?: return
        if (task.status !in setOf("failed", "cancelled")) return
        if (task.type == "import") {
            val payload = runCatching { JSONObject(task.payload) }.getOrNull() ?: run {
                notice("请重新选择文件导入"); return
            }
            val uris = payload.optJSONArray("uris")?.strings()?.map(Uri::parse).orEmpty()
            if (uris.isEmpty()) { notice("请重新选择文件导入"); return }
            queueImport(task, uris, payload.textOrNull("courseId"))
            return
        }
        if (task.type != "download") return
        val credentials = auth ?: run { notice("请先重新登录学校平台"); return }
        val entry = runCatching { ScheduleEntry.from(JSONObject(task.payload)) }.getOrNull() ?: return
        if (!SchoolApi.ownsEntry(credentials, entry)) {
            notice("请使用获取这份课件时的学校账号登录")
            return
        }
        if (!queuedSources.add(entry.id)) return
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                putTask(task.copy(status = "queued", progress = 0, message = "等待下载"))
                downloadSlots.withPermit {
                        if (auth != credentials) throw CancellationException("学校账号已断开")
                        downloadTask(task, entry, credentials)
                    }
            } catch (_: CancellationException) {
                putTask(task.copy(status = "cancelled", message = "已取消"))
            } finally {
                queuedSources.remove(entry.id)
                jobs.remove(id)
            }
        }
        jobs[id] = job
        job.start()
    }

    fun importFiles(uris: List<Uri>, courseId: String? = null) {
        if (uris.isEmpty()) return
        action {
            val resolver = getApplication<Application>().contentResolver
            val distinct = uris.distinct()
            val pdfs = distinct.filter {
                resolver.getType(it) == "application/pdf" || media.displayName(it).endsWith(".pdf", true)
            }
            for (uri in pdfs) {
                queueImport(TaskRecord(type = "import", title = media.displayName(uri)), listOf(uri), courseId)
            }
            val images = distinct - pdfs.toSet()
            if (images.isNotEmpty()) queueImport(TaskRecord(type = "import", title = "导入 ${images.size} 张图片"), images, courseId)
        }
    }

    private fun queueImport(record: TaskRecord, uris: List<Uri>, courseId: String?) {
        if (jobs.containsKey(record.id)) return
        val payload = JSONObject().put("uris", org.json.JSONArray(uris.map(Uri::toString)))
            .put("courseId", courseId).toString()
        val task = record.copy(payload = payload, progress = 0)
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var imported: StudyMaterial? = null
            var committed = false
            var acquired = false
            try {
                putTask(task.copy(status = "queued", message = "等待导入"))
                importSlots.acquire()
                acquired = true
                putTask(task.copy(status = "running", message = "正在导入"))
                val taskContext = coroutineContext
                val progress: (Int, Int) -> Unit = { current, total ->
                    taskContext.ensureActive()
                    putTask(task.copy(status = "running", progress = current * 100 / total,
                        message = "已处理 $current / $total 页"))
                }
                val pdf = uris.size == 1 && (getApplication<Application>().contentResolver.getType(uris.first()) == "application/pdf" ||
                    media.displayName(uris.first()).endsWith(".pdf", true))
                imported = if (pdf) media.importPdf(uris.first(), courseId, progress)
                    else media.importImages(uris, courseId, progress)
                coroutineContext.ensureActive()
                store.put(imported)
                committed = true
                putTask(task.copy(status = "done", progress = 100, message = "导入完成", materialId = imported.id))
                refreshLibrary()
            } catch (error: CancellationException) {
                if (!committed) imported?.let { media.removeIncomplete(it.id) }
                putTask(if (committed) task.copy(status = "done", progress = 100, materialId = imported?.id)
                    else task.copy(status = "cancelled", message = "已取消"))
            } catch (error: Exception) {
                if (!committed) imported?.let { media.removeIncomplete(it.id) }
                putTask(task.copy(status = if (committed) "done" else "failed",
                    materialId = if (committed) imported?.id else null,
                    message = if (error is SecurityException) "文件权限已失效，请重新选择文件导入" else error.message ?: "导入失败"))
            } finally {
                if (acquired) importSlots.release()
                jobs.remove(task.id)
            }
        }
        jobs[task.id] = job
        job.start()
    }

    private var exportJob: Job? = null
    private var lastExportRequest: ExportRequest?=null
    fun retryExport() {lastExportRequest?.let {export(it)}}
    fun sharePage(id: String,page: Int) {
        val captured=state.value.materials.firstOrNull {it.id==id}
        if(exportJob?.isActive==true) {notice("正在准备文件，请稍候");return}
        mutableState.update {it.copy(exportState=ExportState(running=true,message="正在准备当前页"))}
        exportJob=viewModelScope.launch(Dispatchers.IO) {
            var imageFile: File?=null
            try {
                val material=captured ?: store.material(id) ?: error("课件不存在")
                val source=material.pages.getOrNull(page-1) ?: error("页码无效")
                val directory=File(getApplication<Application>().cacheDir,"shared/${newId()}").apply {mkdirs()}
                imageFile=File(directory,"${material.title.replace(Regex("""[\/:*?"<>|]"""),"_").take(80)}_第${page}页.png")
                val bitmap=withPageNote(renderReadingPage(media.file(source.source),source.pdfPage,2400) ?: error("无法读取页面"),material.pageNotes[page].orEmpty())
                try {imageFile.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
                coroutineContext.ensureActive()
                val app=getApplication<Application>()
                val result=ExportResult(androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",imageFile),imageFile.name,"临时分享文件",ExportAction.Share,"image/png")
                mutableState.update {it.copy(exportState=ExportState(result=result,message="当前页已准备好",autoShare=true,origin=id))}
            } catch(error: CancellationException) {imageFile?.delete();mutableState.update {it.copy(exportState=null)};throw error}
            catch(error: Exception) {imageFile?.delete();mutableState.update {it.copy(exportState=ExportState(failed=true,message=error.message?:"无法分享当前页"))}}
        }
    }
    fun savePageImage(id: String,page: Int,chosenUri: Uri?=null,snapshot: StudyMaterial?=null) {
        if(exportJob?.isActive==true) {notice("正在准备文件，请稍候");return}
        val captured=snapshot ?: state.value.materials.firstOrNull {it.id==id}
        mutableState.update {it.copy(exportState=ExportState(running=true,message="正在保存当前页"))}
        exportJob=viewModelScope.launch(Dispatchers.IO) {
            val app=getApplication<Application>()
            var created:Uri?=null
            try {
                val material=captured ?: store.material(id) ?: error("课件不存在")
                val source=material.pages.getOrNull(page-1) ?: error("页码无效")
                val bitmap=withPageNote(renderReadingPage(media.file(source.source),source.pdfPage,2400) ?: error("无法读取页面"),material.pageNotes[page].orEmpty())
                val name=material.title.replace(Regex("""[\/:*?"<>|]"""),"_").take(70)+"_第${page}页.png"
                try {
                    if(android.os.Build.VERSION.SDK_INT>=29 && chosenUri==null) {
                        val values=android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Keye")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING,1)
                        }
                        created=app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("无法创建图片文件")
                    }
                    val uri=chosenUri ?: created ?: error("请选择保存位置")
                    app.contentResolver.openOutputStream(uri)?.use {output ->check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))} ?: error("保存位置不可写")
                    coroutineContext.ensureActive()
                    if(android.os.Build.VERSION.SDK_INT>=29 && created!=null) app.contentResolver.update(uri,android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.IS_PENDING,0)},null,null)
                    mutableState.update {it.copy(exportState=ExportState(result=ExportResult(uri,name,if(created!=null) "图片 / Keye" else "所选位置",ExportAction.Save,"image/png"),message="当前页已保存",origin=id))}
                } finally {bitmap.recycle()}
            } catch(error: CancellationException) {created?.let {app.contentResolver.delete(it,null,null)};mutableState.update {it.copy(exportState=null)};throw error}
            catch(error: Exception) {created?.let {app.contentResolver.delete(it,null,null)};mutableState.update {it.copy(exportState=ExportState(failed=true,message=error.message ?: "无法保存当前页"))}}
        }
    }
    fun consumeShare() {mutableState.update { it.copy(exportState=it.exportState?.copy(autoShare=false)) }}
    fun clearExport() { mutableState.update { it.copy(exportState=null) } }
    fun cancelExport() { exportJob?.cancel() }
    fun export(request: ExportRequest) {
        if(exportJob?.isActive==true) return
        lastExportRequest=request
        mutableState.update { it.copy(exportState=ExportState(running=true,message="正在准备 PDF")) }
        exportJob=viewModelScope.launch(Dispatchers.IO) {
            var staged: File?=null
            try {
                val materials=request.snapshots ?: request.ids.map { store.material(it) ?: error("课件不存在") }
                require(materials.none { it.deleted } && materials.isNotEmpty()) { "课件已移至回收站" }
                staged=File.createTempFile("shared-", ".pdf", File(getApplication<Application>().cacheDir,"shared").apply { mkdirs() })
                val exportContext=coroutineContext
                staged.outputStream().use { out -> media.export(materials,out,request.pages,request.includeNotes) { current,total ->
                    exportContext.ensureActive()
                    mutableState.update { it.copy(exportState=ExportState(running=true,progress=current.toFloat()/total,message="正在生成 $current / $total 页")) }
                } }
                coroutineContext.ensureActive()
                val result=ExportStorage(getApplication()).finish(staged,request) {exportContext.ensureActive()}
                mutableState.update { it.copy(exportState=ExportState(result=result,message=if(request.action==ExportAction.Share) "PDF 已准备好" else "已保存到 ${result.location}",autoShare=request.action!=ExportAction.Save,origin=request.origin)) }
            } catch(error: CancellationException) {
                staged?.delete()
                mutableState.update { it.copy(exportState=ExportState(message="已取消导出")) }
                throw error
            } catch(error: Exception) {
                staged?.delete()
                mutableState.update { it.copy(exportState=ExportState(message=error.message ?: "导出失败，请重试",failed=true,retryable=true)) }
            }
        }
    }

    fun settings(settings: AppSettings) = action {
        store.put(settings)
        mutableState.update { it.copy(settings = settings, notice = "设置已保存") }
    }

}
