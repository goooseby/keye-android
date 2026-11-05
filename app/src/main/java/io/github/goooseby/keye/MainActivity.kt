package io.github.goooseby.keye

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.domain.ClassroomStage
import io.github.goooseby.keye.export.ExportFeedback
import io.github.goooseby.keye.export.ExportSheet
import io.github.goooseby.keye.network.SchoolApi
import io.github.goooseby.keye.ui.AcquireScreen
import io.github.goooseby.keye.ui.CourseScreen
import io.github.goooseby.keye.ui.LibraryScreen
import io.github.goooseby.keye.ui.SchoolLoginScreen
import io.github.goooseby.keye.ui.SettingsScreen
import io.github.goooseby.keye.ui.TasksScreen
import io.github.goooseby.keye.ui.TimetableScreen
import io.github.goooseby.keye.ui.LessonPreparationScreen
import io.github.goooseby.keye.domain.schoolToday
import io.github.goooseby.keye.domain.schoolWeek
import org.json.JSONObject
import io.github.goooseby.keye.ui.components.PageContent
import io.github.goooseby.keye.ui.components.PageMotion
import io.github.goooseby.keye.ui.components.NavigationGlyph
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import io.github.goooseby.keye.ui.components.KeyeTheme
import io.github.goooseby.keye.ui.components.PreferenceRow
import io.github.goooseby.keye.ui.components.SheetHeading
import io.github.goooseby.keye.ui.reader.ClassroomScreen
import io.github.goooseby.keye.ui.reader.ReaderScreen
import io.github.goooseby.keye.update.AppUpdateHost
import io.github.goooseby.keye.update.AppUpdateViewModel
import io.github.goooseby.keye.update.UPDATE_ACTION
import io.github.goooseby.keye.update.UpdateDownloadStage
import java.io.File
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleUpdateIntent(intent)
        setContent { KeyeTheme { KeyeApp(viewModel(), {File(filesDir,it)},viewModel()) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);setIntent(intent);handleUpdateIntent(intent)
    }
    private fun handleUpdateIntent(intent: Intent?) {
        if(intent?.action==UPDATE_ACTION) ViewModelProvider(this)[AppUpdateViewModel::class.java].showDetails()
    }
}
private enum class Section(val title: String,val short: String,val icon: String) {
    Timetable("课表","课表","calendar"), Library("课程","课程","library"), Acquire("批量获取","获取","download"),
    Tasks("任务","任务","tasks"), Trash("回收站","回收站","trash"), Settings("我的","我的","settings")
}
private data class Destination(val section: Section,val course: String?) {
    val depth get()=if(course!=null||section in setOf(Section.Tasks,Section.Acquire,Section.Trash)) 1 else 0
    val key get()=course?.let{"course:$it"} ?: section.name
}

private enum class PageKind(val depth: Int) { Main(0), Preparation(1), Reader(1), Classroom(2), Login(3) }
private data class AppPage(val kind: PageKind,val id: String?=null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyeApp(model: KeyeViewModel,mediaPath: (String) -> File,updates: AppUpdateViewModel) {
    val state by model.state.collectAsState()
    val updateState by updates.state.collectAsState()
    val context=LocalContext.current
    val screens=rememberSaveableStateHolder()
    var section by rememberSaveable{mutableStateOf(Section.Timetable)}
    var tasksOrigin by rememberSaveable{mutableStateOf(Section.Timetable)}
    var courseId by rememberSaveable{mutableStateOf<String?>(null)}
    var materialId by rememberSaveable{mutableStateOf<String?>(null)}
    var acquireOrigin by rememberSaveable {mutableStateOf(Section.Timetable)}
    var exportIds by rememberSaveable{mutableStateOf<List<String>>(emptyList())}
    var shareOnly by rememberSaveable{mutableStateOf(false)}
    var pendingImportCourseId by rememberSaveable{mutableStateOf<String?>(null)}
    var chooseImportCourse by remember{mutableStateOf(false)}
    var classroomOpen by rememberSaveable {mutableStateOf(false)}
    var pendingClassroom by remember {mutableStateOf<ScheduleEntry?>(null)}
    var pendingLesson by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingError by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingStarted by rememberSaveable {mutableStateOf(false)}
    var loginOpen by rememberSaveable{mutableStateOf(false)}
    val snackbar=remember{SnackbarHostState()}
    val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris ->
        if(uris.isNotEmpty()) {
            uris.forEach{uri -> runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
            model.importFiles(uris,pendingImportCourseId);courseId=null;materialId=null;tasksOrigin=Section.Library;section=Section.Tasks
        }
        pendingImportCourseId=null
    }
    fun requestExport(ids: List<String>,name: String) {
        if(state.exportState?.running==true) {model.notice("正在导出，请稍候");return}
        shareOnly=false;exportIds=ids
    }
    fun requestImport(destination: String?) {
        pendingImportCourseId=destination
        importLauncher.launch(arrayOf("image/*","application/pdf"))
    }
    fun requestLogin() {loginOpen=true}
    fun openClassroom(entry: ScheduleEntry) {
        val active=state.classroom
        if(active!=null && active.entry.id!=entry.id && active.stage!=ClassroomStage.Ended) pendingClassroom=entry
        else if(model.startClassroom(entry)) classroomOpen=true
    }
    fun openLesson(entry: ScheduleEntry) {
        if(model.isLiveLesson(entry)) {openClassroom(entry);return}
        val saved=state.materials.firstOrNull {it.sourceKey==entry.id}
        if(saved?.deleted==true) {model.notice("课件在回收站中，请先在我的中恢复");return}
        if(saved!=null && saved.pages.isNotEmpty()) {
            materialId=saved.id
            if(state.loggedIn) model.download(listOf(entry))
        } else {pendingLesson=entry.json().toString();pendingStarted=false;pendingError=null}
    }
    fun goBack() {if(materialId!=null) materialId=null else if(courseId!=null) courseId=null else section=if(section==Section.Tasks) tasksOrigin else if(section==Section.Acquire) acquireOrigin else if(section==Section.Trash) Section.Settings else Section.Timetable}
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_RESUME) model.setSchoolForeground(true)
            if(event==Lifecycle.Event.ON_PAUSE) model.setSchoolForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        model.setSchoolForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer);model.setSchoolForeground(false)}
    }
    LaunchedEffect(lifecycleOwner,loginOpen) {
        if(!loginOpen) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var first=true
            while(true) {
                model.checkSchoolSession(CookieManager.getInstance().getCookie(SchoolApi.HOME),force=first)
                first=false;delay(60_000)
            }
        }
    }
    LaunchedEffect(section,loginOpen) {
        if(!loginOpen && section in setOf(Section.Timetable,Section.Acquire,Section.Settings))
            model.checkSchoolSession(CookieManager.getInstance().getCookie(SchoolApi.HOME))
    }
    val refreshTimetable=section in setOf(Section.Timetable,Section.Library) && !loginOpen && !classroomOpen && materialId==null && courseId==null && pendingLesson==null
    LaunchedEffect(refreshTimetable,state.loggedIn,section) {
        if(refreshTimetable && state.loggedIn) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var first=true
            while(true) {
                val currentWeek=schoolWeek(schoolToday()).toString()
                val week=if(section==Section.Library) currentWeek else model.state.value.timetable.week
                model.loadTimetableWeek(week,force=!first && week==currentWeek)
                first=false;delay(60_000)
            }
        }
    }
    val requestedLesson=remember(pendingLesson) {pendingLesson?.let {runCatching {ScheduleEntry.from(JSONObject(it))}.getOrNull()}}
    LaunchedEffect(requestedLesson?.id,state.loggedIn) {
        if(requestedLesson!=null && state.loggedIn && !pendingStarted) {pendingError=if(model.download(listOf(requestedLesson)) || model.taskForLesson(requestedLesson.id)?.status in setOf("queued","running")) null else model.state.value.notice ?: "暂时无法准备课件";pendingStarted=true}
    }
    val prepared=state.materials.firstOrNull {it.sourceKey==requestedLesson?.id && !it.deleted && it.pages.isNotEmpty()}
    LaunchedEffect(prepared?.id) {if(prepared!=null && requestedLesson!=null) {materialId=prepared.id;pendingLesson=null;pendingStarted=false}}
    LaunchedEffect(state.notice) {state.notice?.let{snackbar.showSnackbar(it);if(model.state.value.notice==it) model.notice(null)}}
    AppUpdateHost(updates,canPrompt=pendingLesson==null && !classroomOpen && materialId==null && !loginOpen && exportIds.isEmpty() && !state.loading)
    if(exportIds.isNotEmpty()) {
        val materials=exportIds.mapNotNull { id ->state.materials.firstOrNull { it.id==id } }
        if(materials.isNotEmpty()) ExportSheet(materials,shareOnly,onDismiss={exportIds=emptyList()},onSubmit={model.export(it.copy(origin=materialId?:section.name));exportIds=emptyList()})
    }
    if(pendingClassroom!=null) AlertDialog(onDismissRequest={pendingClassroom=null},title={Text("切换课堂跟看？")},text={Text("当前已获取的页面和批注会保留。")},confirmButton={TextButton(onClick={pendingClassroom?.let {if(model.startClassroom(it)) classroomOpen=true};pendingClassroom=null}) {Text("切换并保留")}},dismissButton={TextButton(onClick={pendingClassroom=null}) {Text("取消")}})
    LaunchedEffect(classroomOpen,loginOpen,state.loggedIn,state.classroom?.stage) {
        if(classroomOpen && !loginOpen && state.loggedIn && state.classroom?.stage==ClassroomStage.NeedsLogin) model.resumeClassroom()
    }
    if(classroomOpen && state.classroom==null) LaunchedEffect(Unit) {classroomOpen=false}
    val readingMaterial=state.materials.firstOrNull {it.id==materialId && !it.deleted}
    val appPage=when {
        loginOpen ->AppPage(PageKind.Login)
        requestedLesson!=null ->AppPage(PageKind.Preparation,requestedLesson.id)
        classroomOpen && state.classroom!=null ->AppPage(PageKind.Classroom,state.classroom?.entry?.id)
        readingMaterial!=null ->AppPage(PageKind.Reader,readingMaterial.id)
        else ->AppPage(PageKind.Main)
    }
    val mainPages: @Composable () -> Unit = {
        BackHandler(materialId!=null||courseId!=null||section!=Section.Timetable){goBack()}
        val destination=Destination(section,courseId)
        val density=LocalDensity.current
        val navigationInset=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        var navigationHeight by remember(density,navigationInset) {mutableStateOf(80.5.dp+navigationInset)}
        val travelDistance=with(density) {32.dp.roundToPx()}
        val pageTransition=updateTransition(destination,label="navigation")
        val navigationSection=if(destination.depth==0) destination.section else pageTransition.currentState.section
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            pageTransition.AnimatedContent(
                modifier=Modifier.fillMaxSize(),
                contentKey={it.key},
                // Full-screen pages have fixed bounds; animate their layers, not their size.
                transitionSpec={PageMotion.between(initialState.depth,targetState.depth,travelDistance).using(null)}
            ) {route ->
                PageContent(active=route==destination) {
                    screens.SaveableStateProvider(route.key) {
                        Scaffold(containerColor=MaterialTheme.colorScheme.background,
                            topBar={
                                Surface(color=MaterialTheme.colorScheme.background) {
                                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start=if(route.depth==0) 24.dp else 8.dp,end=20.dp),verticalAlignment=Alignment.CenterVertically) {
                                        if(route.depth>0) IconButton(onClick={goBack()}){AppIcon("back","返回")}
                                        Text(when {
                                                route.course!=null -> state.courses.firstOrNull{it.id==route.course}?.title?:"未归类"
                                                else -> route.section.title
                                            },Modifier.weight(1f).padding(start=if(route.depth>0) 4.dp else 0.dp),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        if(route.section in setOf(Section.Timetable,Section.Library) && route.depth==0) TextButton(onClick={acquireOrigin=route.section;section=Section.Acquire}) {AppIcon("download",modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(route.section==Section.Library) "获取课件" else "批量获取",style=MaterialTheme.typography.labelMedium)}
                                        if(route.section in setOf(Section.Timetable,Section.Acquire) && route.course==null) IconButton(onClick={tasksOrigin=route.section;section=Section.Tasks}) {
                                            BadgedBox(badge={val active=state.tasks.count {it.status in setOf("queued","running")};if(active>0) Badge {Text(active.toString())}}) {AppIcon("tasks","下载管理")}
                                        }
                                    }
                                }
                            },
                            // Keep each page's viewport fixed while the shared bar moves over it.
                            bottomBar={if(route.depth==0) Spacer(Modifier.height(navigationHeight))},
                            snackbarHost={if(route==destination) SnackbarHost(snackbar)}
                        ) {padding ->
                            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                if(state.loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                                } else if(state.loadError!=null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                    EmptyState("error","资料库无法读取",detail=state.loadError)
                                } else {
                                    val course=state.courses.firstOrNull{it.id==route.course}
                                    val courseMaterials=remember(state.materials,route.course) {
                                        if(route.course==null) emptyList() else state.materials.filter {
                                            it.courseId==route.course.takeIf(String::isNotEmpty) && !it.deleted
                                        }
                                    }
                                    when {
                                        route.course!=null -> CourseScreen(course,state.courses,courseMaterials,
                                            onOpen={materialId=it},onImport={requestImport(route.course.takeIf(String::isNotEmpty))},
                                            onExport={id,name ->requestExport(listOf(id),name)},onBatchExport={ids,name ->requestExport(ids,name)},mediaPath=mediaPath,model=model)
                                        route.section==Section.Timetable -> TimetableScreen(state,model,{requestLogin()},{openLesson(it)},{openClassroom(it)})
                                        route.section==Section.Library -> LibraryScreen(state,{courseId=it?:""},{materialId=it},{chooseImportCourse=true},
                                            {id,name ->requestExport(listOf(id),name)},{ids,name ->requestExport(ids,name)},mediaPath,model,onFollow={openClassroom(it)},onTimetable={section=Section.Timetable})
                                        route.section==Section.Trash -> LibraryScreen(state,{},{},{},
                                            {id,name ->requestExport(listOf(id),name)},{_,_ ->},mediaPath,model,initialTab=2,trashMode=true)
                                        route.section==Section.Acquire -> AcquireScreen(state,model,{requestLogin()},{model.download(it);tasksOrigin=Section.Acquire;section=Section.Tasks},onOpen={materialId=it},onFollow={openClassroom(it)},onResumeFollow={model.resumeClassroom();classroomOpen=true})
                                        route.section==Section.Tasks -> TasksScreen(state.tasks,model,onOpen={id ->
                                                if(state.materials.any{it.id==id&&!it.deleted}) materialId=id else model.notice("课件已在回收站")
                                            },onOutput={uri ->runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{model.notice("无法打开文件，请确认文件仍存在且已安装 PDF 阅读器")}})
                                        route.section==Section.Settings -> SettingsScreen(state,model,{requestLogin()},onTasks={tasksOrigin=Section.Settings;section=Section.Tasks},onTrash={section=Section.Trash},onUpdates={updates.showDetails()},updateStatus=when(updateState.download.stage) {
                                                UpdateDownloadStage.Downloading ->updateState.download.message
                                                UpdateDownloadStage.Ready ->"${updateState.release?.version} 已下载，点击安装"
                                                else ->updateState.release?.let {"发现新版本 ${it.version}"}
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pageTransition.AnimatedVisibility(
                visible={it.depth==0},
                modifier=Modifier.align(Alignment.BottomCenter),
                enter=PageMotion.navigationEnter,
                exit=PageMotion.navigationExit
            ) {
                Surface(modifier=Modifier.onSizeChanged { navigationHeight=with(density) {it.height.toDp()} },color=MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f),thickness=.5.dp)
                        NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainer,tonalElevation=0.dp) {
                            listOf(Section.Timetable,Section.Library,Section.Settings).forEach{item ->
                                NavigationBarItem(selected=navigationSection==item,onClick={courseId=null;materialId=null;section=item},
                                    icon={NavigationGlyph(item.icon,navigationSection==item)},label={Text(item.short,style=MaterialTheme.typography.labelMedium)},
                                    colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary,
                                        unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
        }
        if(state.exportState!=null) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom=82.dp),contentAlignment=Alignment.BottomCenter) {ExportFeedback(state.exportState,model,section.name)}
        if(chooseImportCourse) ModalBottomSheet(onDismissRequest={chooseImportCourse=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp)) {
                SheetHeading("导入到",{chooseImportCourse=false})
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Column {
                        PreferenceRow("folder","未归类",onClick={chooseImportCourse=false;requestImport(null)})
                        state.courses.sortedBy(Course::title).forEach{course ->
                            HorizontalDivider(Modifier.padding(start=70.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
                            PreferenceRow("library",course.title,course.term.takeIf{it.isNotBlank()},onClick={chooseImportCourse=false;requestImport(course.id)})
                        }
                    }
                }
            }
        }
    }
    val appTravelDistance=with(LocalDensity.current) {32.dp.roundToPx()}
    AnimatedContent(appPage,modifier=Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        transitionSpec={PageMotion.between(initialState.kind.depth,targetState.kind.depth,appTravelDistance).using(null)},
        label="full screen navigation") {page ->
        PageContent(active=page==appPage) {
            when(page.kind) {
                PageKind.Login ->SchoolLoginScreen(model,{loginOpen=false})
                PageKind.Preparation -> {
                    // Retain outgoing content until its animation finishes, even after navigation clears the request.
                    val lesson=remember(page.id) {requestedLesson}
                    if(lesson!=null) LessonPreparationScreen(lesson,state,model,
                        onBack={pendingLesson=null;pendingStarted=false},
                        onLogin={pendingStarted=false;pendingError=null;requestLogin()},
                        onRetry={pendingError=if(model.download(listOf(lesson))) null else model.state.value.notice ?: "暂时无法准备课件";pendingStarted=true},error=pendingError)
                }
                PageKind.Classroom -> {
                    val initialSession=remember(page.id) {state.classroom}
                    val session=state.classroom?.takeIf {it.entry.id==page.id} ?: initialSession
                    if(session!=null) ClassroomScreen(session,state.materials.firstOrNull {it.id==session.materialId},state,model,mediaPath,
                        onBack={classroomOpen=false},onLogin={requestLogin()})
                }
                PageKind.Reader -> {
                    val initialMaterial=remember(page.id) {readingMaterial}
                    val material=state.materials.firstOrNull {it.id==page.id && !it.deleted} ?: initialMaterial
                    if(material!=null) ReaderScreen(material,model,mediaPath,onBack={materialId=null},
                        onExport={requestExport(listOf(material.id),material.title)},onShare={
                            if(state.exportState?.running==true) model.notice("正在准备文件，请稍候") else {shareOnly=true;exportIds=listOf(material.id)}
                        },exportState=state.exportState,onClassroom={openClassroom(it)})
                }
                PageKind.Main ->mainPages()
            }
        }
    }
}
