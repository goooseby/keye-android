package io.github.goooseby.keye

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class UiState(
    val exportState: ExportState? = null,
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

class KeyeViewModel(application: Application) : AndroidViewModel(application) {
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

    fun lastPage(id: String, page: Int, offset: Int = 0, fraction: Float=0f) = action {
        materialMutex.withLock {
            val current = store.material(id) ?: return@withLock
            val updated = current.copy(lastPage=page.coerceIn(1,current.pages.size.coerceAtLeast(1)), readOffset=offset.coerceAtLeast(0), readFraction=fraction.coerceIn(0f,.999f), readAt=System.currentTimeMillis())
            store.put(updated)
            mutableState.update { state -> state.copy(materials=state.materials.map { if(it.id==id) updated else it }) }
        }
    }

    fun bookmark(id: String, page: Int) = action {
        editMaterial(id) { current -> current.copy(bookmarks=if(page in current.bookmarks) current.bookmarks-page else current.bookmarks+page) }
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
            if(!SchoolApi.ownsEntry(credentials,entry)) {notice("请使用查询这些课次的学校账号登录");false}
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
        if (existing != null) {
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
                        )
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
            val material = StudyMaterial(
                id = task.id, sourceKey = entry.id, title = entry.title,
                day = entry.day, courseId = entry.groupId, kind = "images", pages = pages
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
        if(exportJob?.isActive==true) {notice("正在准备文件，请稍候");return}
        mutableState.update {it.copy(exportState=ExportState(running=true,message="正在准备当前页"))}
        exportJob=viewModelScope.launch(Dispatchers.IO) {
            var imageFile: File?=null
            try {
                val material=store.material(id) ?: error("课件不存在")
                val source=material.pages.getOrNull(page-1) ?: error("页码无效")
                val directory=File(getApplication<Application>().cacheDir,"shared/${newId()}").apply {mkdirs()}
                imageFile=File(directory,"${material.title.replace(Regex("""[\/:*?"<>|]"""),"_").take(80)}_第${page}页.png")
                val bitmap=renderReadingPage(media.file(source.source),source.pdfPage,2400) ?: error("无法读取页面")
                try {imageFile.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
                coroutineContext.ensureActive()
                val app=getApplication<Application>()
                val result=ExportResult(androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",imageFile),imageFile.name,"临时分享文件",ExportAction.Share,"image/png")
                mutableState.update {it.copy(exportState=ExportState(result=result,message="当前页已准备好",autoShare=true,origin=id))}
            } catch(error: CancellationException) {imageFile?.delete();mutableState.update {it.copy(exportState=null)};throw error}
            catch(error: Exception) {imageFile?.delete();mutableState.update {it.copy(exportState=ExportState(failed=true,message=error.message?:"无法分享当前页"))}}
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
                val materials=request.ids.map { store.material(it) ?: error("课件不存在") }
                require(materials.none { it.deleted } && materials.isNotEmpty()) { "课件已移至回收站" }
                staged=File.createTempFile("shared-", ".pdf", File(getApplication<Application>().cacheDir,"shared").apply { mkdirs() })
                val exportContext=coroutineContext
                staged.outputStream().use { out -> media.export(materials,out,request.pages) { current,total ->
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
