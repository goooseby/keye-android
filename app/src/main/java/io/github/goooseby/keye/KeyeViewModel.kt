package io.github.goooseby.keye

import io.github.goooseby.keye.data.CachedSchedule
import io.github.goooseby.keye.domain.schoolToday
import io.github.goooseby.keye.domain.schoolWeek
import io.github.goooseby.keye.domain.scheduleIsFresh
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.goooseby.keye.data.AppSettings
import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.LibraryStore
import io.github.goooseby.keye.data.MediaLibrary
import io.github.goooseby.keye.data.PageFile
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.data.TaskRecord
import io.github.goooseby.keye.data.mergeTaskBatch
import io.github.goooseby.keye.data.newId
import io.github.goooseby.keye.data.renderReadingPage
import io.github.goooseby.keye.data.strings
import io.github.goooseby.keye.data.taskQueueOrder
import io.github.goooseby.keye.data.textOrNull
import io.github.goooseby.keye.data.withPageNote
import io.github.goooseby.keye.domain.ClassroomPage
import io.github.goooseby.keye.domain.ClassroomPageSource
import io.github.goooseby.keye.domain.ClassroomSession
import io.github.goooseby.keye.domain.ClassroomStage
import io.github.goooseby.keye.domain.classroomPageKey
import io.github.goooseby.keye.domain.classroomRetryMillis
import io.github.goooseby.keye.domain.insertClassroomPage
import io.github.goooseby.keye.domain.pageRemovalResult
import io.github.goooseby.keye.domain.pendingClassroomPages
import io.github.goooseby.keye.export.ExportAction
import io.github.goooseby.keye.export.ExportRequest
import io.github.goooseby.keye.export.ExportResult
import io.github.goooseby.keye.export.ExportState
import io.github.goooseby.keye.export.ExportStorage
import io.github.goooseby.keye.network.SchoolApi
import io.github.goooseby.keye.network.SchoolAuth
import io.github.goooseby.keye.network.SchoolConnection
import io.github.goooseby.keye.network.SchoolRateLimited
import io.github.goooseby.keye.network.SchoolSessionExpired
import io.github.goooseby.keye.network.hasSchoolLoginCookie
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import io.github.goooseby.keye.domain.shouldRefreshScheduleWeek
import org.json.JSONObject

data class TimetableState(
    val week: String=schoolWeek(schoolToday()).toString(),val entries: List<ScheduleEntry> = emptyList(),
    val loading: Boolean=false,val checkedAt: Long=0,val error: String?=null,val fromCache: Boolean=true
)

data class UiState(
    val timetable: TimetableState = TimetableState(),
    val hasTimetableCache: Boolean = false,
    val exportState: ExportState? = null,
    val classroom: ClassroomSession? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    val courses: List<Course> = emptyList(),
    val materials: List<StudyMaterial> = emptyList(),
    val tasks: List<TaskRecord> = emptyList(),
    val schedule: List<ScheduleEntry> = emptyList(),
    val scheduleCheckedAt: Long=0,
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
    @Volatile private var lastSessionCheck=0L
    @Volatile private var rejectedCookie:String?=null
    private val timetablePrefs=application.getSharedPreferences("timetable",0)
    @Volatile private var timetableAccount:String?=timetablePrefs.getString("last_account",null)
    private data class WeekRequest(val credentials:SchoolAuth,val result:Deferred<CachedSchedule>)
    private val weekRequests=ConcurrentHashMap<String,WeekRequest>()
    private val verifiedWeeks=ConcurrentHashMap.newKeySet<String>()
    private val weekMemory=ConcurrentHashMap<String,CachedSchedule>()
    private val weekRequestLock=Any()
    private val weekSlots=Semaphore(2)
    private var timetablePrefetch:Job?=null
    private var prefetchAnchor:String?=null
    @Volatile private var schoolForeground=true
    private var timetableJob: Job?=null
    private val timetableSequence=java.util.concurrent.atomic.AtomicLong()
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
                val account=timetableAccount
                val week=schoolWeek(schoolToday()).toString()
                val cached=account?.let {store.scheduleWeek(it,week)}
                if(cached!=null) weekMemory["$account:$week"]=cached
                val hasCache=account?.let {store.hasScheduleWeeks(it)}==true
                mutableState.update { it.copy(loading=false,courses=courses,materials=materials,tasks=interrupted,settings=settings,
                    hasTimetableCache=it.hasTimetableCache || (account==timetableAccount && hasCache),
                    timetable=if(it.timetable.checkedAt==0L && it.timetable.week==week && cached!=null && account==timetableAccount) TimetableState(week,cached.entries,it.timetable.loading,cached.checkedAt) else it.timetable) }
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
            activateTimetableAccount(credentials)
            auth=credentials;rejectedCookie=null
            lastSessionCheck=android.os.SystemClock.elapsedRealtime()
            android.webkit.CookieManager.getInstance().flush()
            mutableState.update {it.copy(loggedIn=true,schoolConnection=SchoolConnection.Connected,notice=if(silent) null else "已连接学校课堂平台")}
            loadTimetableWeek(schoolWeek(schoolToday()).toString())
            true
        } catch(error: CancellationException) {throw error}
        catch(error: Exception) {
            if(sequence==sessionSequence.get()) {
                if(error is SchoolSessionExpired) {
                    rejectedCookie=credentials.cookie;clearTimetable();auth=null
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
                clearTimetable();auth=null
                mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.Expired,schedule=emptyList(),busy=false)}
            }
            return
        }
        if(credentials.cookie==rejectedCookie) return
        if(!force && credentials==auth && android.os.SystemClock.elapsedRealtime()-lastSessionCheck<60_000) return
        val sequence=sessionSequence.incrementAndGet()
        val warmSchedule=auth==null
        activateTimetableAccount(credentials)
        auth=credentials
        lastSessionCheck=android.os.SystemClock.elapsedRealtime()
        mutableState.update {it.copy(loggedIn=true,schoolConnection=
            if(it.schoolConnection in setOf(SchoolConnection.Connected,SchoolConnection.Unverified)) it.schoolConnection else SchoolConnection.Checking)}
        if(warmSchedule) loadTimetableWeek(schoolWeek(schoolToday()).toString())
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
        rejectedCookie=credentials.cookie;clearTimetable();auth=null
        mutableState.update {it.copy(loggedIn=false,schoolConnection=SchoolConnection.Expired,busy=false,schedule=emptyList(),notice="学校登录已失效，请重新登录")}
        // CookieManager is intentionally untouched: only an explicit sign-out
        // removes cookies, so the school login page may still reuse its SSO.
    }

    private fun clearTimetable() {
        timetableSequence.incrementAndGet();timetableJob?.cancel();timetablePrefetch?.cancel()
        weekRequests.values.forEach {it.result.cancel()};weekRequests.clear();verifiedWeeks.clear();prefetchAnchor=null
        // Session expiry never deletes the locally saved timetable.
        mutableState.update {it.copy(timetable=it.timetable.copy(loading=false,fromCache=true,error=null))}
    }

    private fun activateTimetableAccount(credentials: SchoolAuth) {
        val account=java.security.MessageDigest.getInstance("SHA-256").digest("${credentials.tenantId}:${credentials.userId}".toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        if(account!=timetableAccount) {
            clearTimetable();weekMemory.clear();timetableAccount=account
            timetablePrefs.edit().putString("last_account",account).apply()
            mutableState.update {it.copy(timetable=TimetableState(week=it.timetable.week),hasTimetableCache=false)}
        }
    }

    private fun fetchScheduleWeek(credentials: SchoolAuth,account: String,start: LocalDate): Deferred<CachedSchedule> = synchronized(weekRequestLock) {
        val key="$account:$start"
        weekRequests[key]?.let {if(it.credentials==credentials && it.result.isActive) return@synchronized it.result else it.result.cancel()}
        val deferred=viewModelScope.async(Dispatchers.IO,start=CoroutineStart.LAZY) {
            try {
                val entries=weekSlots.withPermit {school.scan(credentials,start,start.plusDays(6),state.value.settings.timeoutSeconds.coerceAtMost(15))}
                coroutineContext.ensureActive()
                if(auth!=credentials || timetableAccount!=account) throw CancellationException("学校账号已变化")
                val result=CachedSchedule(entries,System.currentTimeMillis())
                store.putScheduleWeek(account,start.toString(),result);weekMemory[key]=result
                if(auth!=credentials || timetableAccount!=account) throw CancellationException("学校账号已变化")
                verifiedWeeks.add(key)
                lastSessionCheck=android.os.SystemClock.elapsedRealtime()
                mutableState.update {if(auth==credentials && timetableAccount==account) it.copy(hasTimetableCache=true,schoolConnection=SchoolConnection.Connected) else it}
                result
            } catch(error:Exception) {
                if(auth!=credentials) throw CancellationException("学校账号已变化")
                throw error
            }
        }
        val request=WeekRequest(credentials,deferred);weekRequests[key]=request
        deferred.invokeOnCompletion {weekRequests.remove(key,request)}
        deferred.start();deferred
    }

    private fun prefetchSchedule(credentials: SchoolAuth,account: String,week: LocalDate,direction:Int=0) {
        if(!schoolForeground) return
        val anchor="$account:$week:$direction"
        synchronized(weekRequestLock) {
            if(prefetchAnchor==anchor) return
            prefetchAnchor=anchor
            timetablePrefetch?.cancel()
            timetablePrefetch=viewModelScope.launch(Dispatchers.IO) {
                val offsets=when {direction<0 ->listOf(-1L,-2L,1L);direction>0 ->listOf(1L,2L,-1L);else ->listOf(-1L,1L)}
                for(offset in offsets) {
                    if(!schoolForeground || auth!=credentials || timetableAccount!=account) break
                    val target=week.plusWeeks(offset);val key="$account:$target"
                    val cached=weekMemory[key] ?: store.scheduleWeek(account,target.toString())?.also {weekMemory[key]=it}
                    if(cached!=null && !shouldRefreshScheduleWeek(target,cached.checkedAt)) continue
                    try {fetchScheduleWeek(credentials,account,target).await()}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(error:Exception) {if(error is SchoolSessionExpired) {expireSchoolSession(credentials);break}}
                    kotlinx.coroutines.delay(300)
                }
            }
        }
    }

    fun loadTimetableWeek(date: String=state.value.timetable.week,force: Boolean=false) {
        val start=runCatching {schoolWeek(LocalDate.parse(date))}.getOrElse {return}
        val account=timetableAccount ?: return
        val credentials=auth
        val week=start.toString();val key="$account:$week"
        val current=state.value.timetable
        val direction=week.compareTo(current.week).coerceIn(-1,1)
        if(current.week==week && timetableJob?.isActive==true && !force) return
        val memory=weekMemory[key]
        val needsNetwork=credentials!=null && (force || memory==null || shouldRefreshScheduleWeek(start,memory.checkedAt) || (start==schoolWeek(schoolToday()) && key !in verifiedWeeks))
        // Even a cache hit changes the visible week: an older awaited request
        // may still finish and must not replace the user's newer selection.
        val sequence=timetableSequence.incrementAndGet();timetableJob?.cancel()
        if(memory!=null && !needsNetwork) {
            mutableState.update {it.copy(hasTimetableCache=true,timetable=TimetableState(week,memory.entries,false,memory.checkedAt,fromCache=credentials==null || key !in verifiedWeeks))}
            if(credentials!=null) prefetchSchedule(credentials,account,start,direction)
            return
        }
        mutableState.update {it.copy(timetable=when {
            memory!=null ->TimetableState(week,memory.entries,needsNetwork,memory.checkedAt,fromCache=credentials==null || key !in verifiedWeeks)
            it.timetable.week==week ->it.timetable.copy(loading=credentials!=null,error=null)
            else ->TimetableState(week=week,loading=credentials!=null)
        })}
        timetableJob=viewModelScope.launch(Dispatchers.IO) {
            fun valid()=sequence==timetableSequence.get() && timetableAccount==account
            try {
                val cached=memory ?: store.scheduleWeek(account,week)?.also {weekMemory[key]=it}
                if(valid() && cached!=null) mutableState.update {it.copy(hasTimetableCache=true,timetable=TimetableState(week,cached.entries,credentials!=null,cached.checkedAt,fromCache=credentials==null || key !in verifiedWeeks))}
                if(credentials==null || auth!=credentials) return@launch
                if(!force && cached!=null && !shouldRefreshScheduleWeek(start,cached.checkedAt) && (start!=schoolWeek(schoolToday()) || key in verifiedWeeks)) {
                    prefetchSchedule(credentials,account,start,direction)
                    return@launch
                }
                val result=fetchScheduleWeek(credentials,account,start).await()
                if(valid() && auth==credentials) {
                    mutableState.update {it.copy(timetable=TimetableState(week,result.entries,false,result.checkedAt,fromCache=false))}
                    prefetchSchedule(credentials,account,start,direction)
                }
            } catch(cancel:CancellationException) {throw cancel}
            catch(error:Exception) {
                if(valid()) {
                    if(error is SchoolSessionExpired && credentials!=null) expireSchoolSession(credentials)
                    else mutableState.update {it.copy(timetable=it.timetable.copy(loading=false,fromCache=true,error="暂时无法更新，显示已保存课表"))}
                }
            } finally {if(valid()) mutableState.update {it.copy(timetable=it.timetable.copy(loading=false))}}
        }
    }

    fun setSchoolForeground(value: Boolean) {
        schoolForeground=value
        if(!value) {timetablePrefetch?.cancel();prefetchAnchor=null}
    }

    fun taskForLesson(id: String): TaskRecord?=state.value.tasks.firstOrNull {
        it.type=="download" && runCatching {JSONObject(it.payload).optString("id")==id}.getOrDefault(false)
    }

    fun isLiveLesson(entry: ScheduleEntry): Boolean = state.value.loggedIn && state.value.schoolConnection==SchoolConnection.Connected && !state.value.timetable.fromCache && entry.status==1 &&
        state.value.timetable.error==null && scheduleIsFresh(state.value.timetable.checkedAt) &&
        state.value.timetable.entries.any {it.id==entry.id && it.status==1}

    fun logout() {
        clearTimetable()
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
                mutableState.update { it.copy(schedule = entries, scheduleCheckedAt=System.currentTimeMillis(), schoolConnection=SchoolConnection.Connected, notice = "找到 ${entries.size} 节课") }
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
    private fun createPageSource(live: Boolean=false): ClassroomPageSource? {
        val credentials=auth
        return classroomSourceFactory?.invoke() ?: credentials?.let {captured ->object: ClassroomPageSource {
            override val credentials=captured
            override fun owns(entry: ScheduleEntry)=SchoolApi.ownsEntry(captured,entry)
            private fun checkAccount() {if(auth?.jwt!=captured.jwt || auth?.userId!=captured.userId) throw SchoolSessionExpired()}
            override fun pages(entry: ScheduleEntry): List<ClassroomPage> {checkAccount();return school.imagePages(captured,entry,if(live) state.value.settings.timeoutSeconds.coerceAtMost(12) else state.value.settings.timeoutSeconds)}
            override fun image(page: ClassroomPage): ByteArray {checkAccount();return school.image(captured,page.url,if(live) state.value.settings.timeoutSeconds.coerceAtMost(12) else state.value.settings.timeoutSeconds)}
        }}
    }
    fun startClassroom(entry: ScheduleEntry): Boolean {
        val previous=state.value.classroom
        if(previous?.entry?.id==entry.id && classroomJob?.isActive==true) {resumeClassroom();return true}
        if(entry.id in queuedSources) {notice("此课次正在下载，请完成或取消下载后再跟看");return false}
        val interval=getApplication<Application>().getSharedPreferences("classroom",0).getInt("interval",3).takeIf {it in setOf(3,5,10)} ?: 3
        val source=createPageSource(live=true)
        val generation=classroomGeneration.incrementAndGet()
        classroomJob?.cancel();classroomManuallyPaused=false
        mutableState.update {it.copy(classroom=ClassroomSession(entry,previous?.takeIf {it.entry.id==entry.id}?.materialId ?: state.value.materials.firstOrNull {it.sourceKey==entry.id && !it.deleted}?.id,
            stage=if(source==null) ClassroomStage.NeedsLogin else ClassroomStage.Connecting,intervalSeconds=interval,
            message=if(source==null) "请登录学校平台后继续，已有页面仍可阅读" else "正在连接课堂"))}
        if(source==null) return true
        if(!source.owns(entry)) {
            mutableState.update {it.copy(classroom=it.classroom?.copy(stage=ClassroomStage.NeedsLogin,message="请使用查询此课次的学校账号登录"))};return false
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
                            editMaterial(initial.id) {it.copy(remotePageCount=maxOf(it.remotePageCount ?: 0,catalog.size))}
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
        return true
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

    private fun legacyHashes(material: StudyMaterial): Map<String,List<Byte>> = material.pages.filter {it.remoteKey==null}.mapNotNull {page ->
        runCatching {page.source to java.security.MessageDigest.getInstance("SHA-256").digest(media.file(page.source).readBytes()).toList()}.getOrNull()
    }.toMap()

    private suspend fun commitSchoolPage(id: String,remote: ClassroomPage,bytes: ByteArray,catalog: List<ClassroomPage>,legacy: Map<String,List<Byte>>) {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        var created:PageFile?=null
        var committed=false
        try {
            materialMutex.withLock {
                coroutineContext.ensureActive()
                val latest=store.material(id) ?: error("课件不存在")
                check(!latest.deleted) {"课件在回收站中，请先恢复"}
                if(latest.pages.any {it.remoteKey==remote.key}) return@withLock
                val matched=legacy.entries.firstOrNull {(source,digest) ->digest==hash && latest.pages.any {it.source==source && it.remoteKey==null}}?.key
                val changed=if(matched!=null) latest.copy(pages=latest.pages.map {if(it.source==matched) it.copy(remoteKey=remote.key) else it})
                    else {created=media.classroomImage(id,remote.key,bytes);insertClassroomPage(latest,created!!,catalog)}
                coroutineContext.ensureActive()
                store.put(changed);committed=true
                mutableState.update {it.copy(materials=it.materials.filterNot {m ->m.id==id}+changed)}
            }
        } finally {if(!committed) created?.let(media::discardClassroomPage)}
    }

    private fun releasePausedClassroom(entry: ScheduleEntry): Boolean {
        val session=state.value.classroom
        if(session?.entry?.id!=entry.id || session.stage==ClassroomStage.Ended) return true
        if(classroomForeground) {notice("请先退出跟看，再补全课件");return false}
        endClassroom()
        return true
    }

    fun download(entries: List<ScheduleEntry>): Boolean {
        val source=createPageSource() ?: run {notice("请先登录学校平台");return false}
        val accepted=entries.distinctBy(ScheduleEntry::id).filter {entry ->
            if(!source.owns(entry)) {notice("请使用查询这些课次的学校账号登录");false}
            else releasePausedClassroom(entry) && queuedSources.add(entry.id)
        }
        if(accepted.isEmpty()) return false
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
                                downloadTask(task,entry,source)
                            }
                        } catch(_: CancellationException) {if(state.value.tasks.firstOrNull {it.id==task.id}?.status!="cancelled") putTask(task.copy(status="cancelled",message="已取消"))}
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
        return true
    }

    private suspend fun downloadTask(task: TaskRecord, entry: ScheduleEntry, source: ClassroomPageSource) {
        var materialId:String?=null
        try {
            putTask(task.copy(status="running",message="正在检查可用页面"))
            val settings=state.value.settings
            val catalog=source.pages(entry).distinctBy {it.key}
            require(catalog.isNotEmpty()) {"这节课暂时没有可用页面，已有内容已保留"}
            coroutineContext.ensureActive()
            if(source.credentials!=null && auth!=source.credentials) throw CancellationException("学校账号已断开")
            val material=materialMutex.withLock {
                val old=store.materialBySource(entry.id)
                check(old?.deleted!=true) {"课件在回收站中，请先恢复"}
                check(old==null || old.kind=="images") {"此课件类型不支持补全"}
                val current=(old ?: StudyMaterial(id=task.id,sourceKey=entry.id,title=entry.title,day=entry.day,courseId=entry.groupId,kind="images",pages=emptyList()))
                    .copy(schoolEntry=entry,remotePageCount=maxOf(old?.remotePageCount ?: 0,catalog.size))
                if(store.courses().none {it.id==entry.groupId}) store.put(Course(id=entry.groupId,title=entry.title))
                store.put(current);refreshLibrary();current
            }
            materialId=material.id
            val legacy=legacyHashes(material)
            val pending=pendingClassroomPages(material.pages.mapNotNull {it.remoteKey}.toSet(),catalog)
            for((index,remote) in pending.withIndex()) {
                coroutineContext.ensureActive()
                var bytes:ByteArray?=null
                var failure:Exception?=null
                for(attempt in 1..settings.retryCount) {
                    try {bytes=source.image(remote);break}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(error:Exception) {
                        if(error is SchoolSessionExpired || error is SchoolRateLimited) throw error
                        failure=error
                        if(attempt<settings.retryCount) kotlinx.coroutines.delay(300L*attempt)
                    }
                }
                coroutineContext.ensureActive()
                if(source.credentials!=null && auth!=source.credentials) throw CancellationException("学校账号已断开")
                commitSchoolPage(material.id,remote,bytes ?: throw failure ?: IllegalStateException("页面下载失败"),catalog,legacy)
                putTask(task.copy(status="running",progress=(index+1)*100/pending.size,message="已补全 ${index+1} / ${pending.size} 页",materialId=material.id))
                if(settings.requestGapMillis>0) kotlinx.coroutines.delay(settings.requestGapMillis)
            }
            putTask(task.copy(status="done",progress=100,message=if(pending.isEmpty()) "没有新增页面" else "已同步本次可用的 ${catalog.size} 页",materialId=material.id))
        } catch(cancel:CancellationException) {
            putTask(task.copy(status="cancelled",message="已取消，已保存页面可继续补全",materialId=materialId));throw cancel
        } catch(error:Exception) {
            if(error is SchoolSessionExpired) source.credentials?.let {expireSchoolSession(it)}
            putTask(task.copy(status="failed",message=error.message ?: "同步失败，已保存页面已保留",materialId=materialId))
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
        val source=createPageSource() ?: run {notice("请先重新登录学校平台");return}
        val entry = runCatching { ScheduleEntry.from(JSONObject(task.payload)) }.getOrNull() ?: return
        if (!source.owns(entry)) {
            notice("请使用获取这份课件时的学校账号登录")
            return
        }
        if(!releasePausedClassroom(entry) || !queuedSources.add(entry.id)) return
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                putTask(task.copy(status = "queued", progress = 0, message = "等待下载"))
                downloadSlots.withPermit {
                        downloadTask(task, entry, source)
                    }
            } catch (_: CancellationException) {
                if(state.value.tasks.firstOrNull {it.id==task.id}?.status!="cancelled") putTask(task.copy(status = "cancelled", message = "已取消"))
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
