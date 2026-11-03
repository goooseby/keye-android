package io.github.goooseby.keye

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class,FlowPreview::class)
@Composable
fun ClassroomScreen(session: ClassroomSession,material: StudyMaterial?,state: UiState,model: KeyeViewModel,
                    mediaPath: (String)->File,onBack: ()->Unit,onLogin: ()->Unit) {
    val context=LocalContext.current
    val activity=context as Activity
    val owner=LocalLifecycleOwner.current
    var following by rememberSaveable(session.entry.id) {mutableStateOf(true)}
    var seenCount by rememberSaveable(session.entry.id) {mutableIntStateOf(material?.pages?.size ?: 0)}
    var fullscreen by rememberSaveable {mutableStateOf(false)}
    var chrome by rememberSaveable {mutableStateOf(true)}
    var controlTick by remember {mutableIntStateOf(0)}
    var scrubbing by remember {mutableStateOf(false)}
    var zoomed by remember {mutableStateOf(false)}
    var resetToken by remember {mutableIntStateOf(0)}
    var panel by remember {mutableStateOf<String?>(null)}
    var endConfirmation by remember {mutableStateOf(false)}
    var exportSnapshot by remember {mutableStateOf<StudyMaterial?>(null)}
    var imageSavePage by rememberSaveable {mutableIntStateOf(1)}
    val originalIndices=remember(material?.pages,material?.excluded) {material?.let {retainedPageIndices(it.pages.size,it.excluded)} ?: emptyList()}
    val pages=originalIndices.map {material!!.pages[it]}
    val count=pages.size
    val latestCount by rememberUpdatedState(count)
    val latestIndices by rememberUpdatedState(originalIndices)
    val pager=rememberPagerState(initialPage=if(originalIndices.isEmpty()) 0 else visiblePositionForPage(originalIndices,(material?.lastPage ?: 1)-1),pageCount={count.coerceAtLeast(1)})
    val current=pager.currentPage.coerceIn(0,(count-1).coerceAtLeast(0))
    val currentPage=(originalIndices.getOrNull(current) ?: 0)+1
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    val saveLocation=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {uri ->
        if(uri!=null && material!=null) model.savePageImage(material.id,imageSavePage,uri)
    }
    fun goTo(page: Int) {
        following=false;controlTick++;resetToken++
        scope.launch {pager.animateScrollToPage(page.coerceIn(0,(count-1).coerceAtLeast(0)))}
    }
    fun followLatest() {
        following=true;seenCount=count;resetToken++;controlTick++
        scope.launch {if(count>0) pager.scrollToPage(count-1)}
    }
    fun setFull(value: Boolean) {fullscreen=value;chrome=true;panel=null;resetToken++;zoomed=false;controlTick++}
    BackHandler {if(fullscreen) setFull(false) else onBack()}
    DisposableEffect(owner,session.entry.id) {
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_RESUME) model.setClassroomForeground(true)
            if(event==Lifecycle.Event.ON_PAUSE) model.setClassroomForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        model.setClassroomForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {owner.lifecycle.removeObserver(observer);model.setClassroomForeground(false)}
    }
    DisposableEffect(fullscreen) {
        activity.requestedOrientation=if(fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if(!activity.isChangingConfigurations) {bars.show(WindowInsetsCompat.Type.systemBars());activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        }
    }
    LaunchedEffect(dragged) {if(dragged) following=false}
    LaunchedEffect(count,following) {
        if(count>0) {
            val target=classroomAppendTarget(following,pager.currentPage,count)
            if(following || pager.currentPage>=count) {resetToken++;pager.scrollToPage(target)}
            if(following) seenCount=count
        }
    }
    LaunchedEffect(current,count) {if(count>0 && current==count-1) seenCount=count}
    LaunchedEffect(session.materialId) {
        snapshotFlow {pager.settledPage to latestCount}.distinctUntilChanged().debounce(250).collect {(page,_) ->
            val id=session.materialId
            if(id!=null) latestIndices.getOrNull(page)?.let {model.lastPage(id,it+1)}
        }
    }
    LaunchedEffect(fullscreen,chrome,controlTick,scrubbing,panel) {
        val accessibility=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if(fullscreen&&chrome&&!scrubbing&&panel==null&&accessibility?.isTouchExplorationEnabled!=true) {delay(3000);chrome=false}
    }
    LaunchedEffect(state.notice) {state.notice?.let {snackbar.showSnackbar(it);if(model.state.value.notice==it) model.notice(null)}}
    Column(Modifier.fillMaxSize().background(if(fullscreen) Color.Black else MaterialTheme.colorScheme.surfaceContainer)) {
        if(!fullscreen) Surface {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onBack) {AppIcon("back","返回并暂停跟看")}
                    Column(Modifier.weight(1f)) {
                        Text(session.entry.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("课堂跟看 · ${session.entry.day}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick={model.refreshClassroom()},enabled=!session.fetching && session.stage in setOf(ClassroomStage.Connected,ClassroomStage.Waiting,ClassroomStage.Retrying)) {AppIcon("refresh","立即检查新页面")}
                    IconButton(onClick={panel="settings"}) {AppIcon("more","跟看设置")}
                }
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    val status=when(session.stage) {
                        ClassroomStage.Connecting ->"连接中"
                        ClassroomStage.Waiting ->if(count>0) "等待新增" else "等待页面"
                        ClassroomStage.Connected ->if(session.fetching) "同步中" else "已连接"
                        ClassroomStage.Paused ->"已暂停"
                        ClassroomStage.Retrying ->"正在重试"
                        ClassroomStage.NeedsLogin ->"需登录"
                        ClassroomStage.Ended ->"已结束"
                    }
                    StatusPill(status,if(session.stage==ClassroomStage.NeedsLogin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(when(session.stage) {
                        ClassroomStage.Retrying ->"${session.message} · ${session.retrySeconds} 秒后重试"
                        ClassroomStage.NeedsLogin,ClassroomStage.Ended,ClassroomStage.Paused ->session.message
                        else ->if(session.fetching) session.message else "$count 页已保存 · 每 ${session.intervalSeconds} 秒检查"
                    },Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(session.stage==ClassroomStage.NeedsLogin) TextButton(onClick=onLogin) {Text("登录")}
                    if(session.stage in setOf(ClassroomStage.Paused,ClassroomStage.Ended)) TextButton(onClick=model::resumeClassroom) {Text("继续")}
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(count==0 || material?.deleted==true) EmptyState("live",if(material?.deleted==true) "课件已移入回收站" else "等待课件页面",detail=if(session.stage==ClassroomStage.NeedsLogin) "登录后可继续同步" else "新页面出现后会显示在这里",modifier=Modifier.align(Alignment.Center))
            else HorizontalPager(pager,Modifier.fillMaxSize(),userScrollEnabled=!zoomed,beyondViewportPageCount=1,key={pages[it].source}) {position ->
                ReaderCanvas(pages[position],mediaPath,Modifier.fillMaxSize(),originalIndices[position]+1,false,position==current,resetToken,
                    onTap={if(fullscreen) chrome=!chrome},onZoomed={if(position==current) {zoomed=it;if(it) following=false}})
            }
            if(fullscreen && count>0 && material!=null) {
                FullscreenReaderControls(chrome,"${if(following) "跟随中" else "回看中"} · ${session.entry.title}",current,currentPage,material.pages.size,count,currentPage in material.bookmarks,zoomed,
                    {setFull(false)},{goTo(current-1)},{goTo(current+1)},{goTo(it)},{scrubbing=it;if(it) following=false;controlTick++},
                    {model.bookmark(material.id,currentPage);controlTick++},{resetToken++;controlTick++})
                if(!following) FilledTonalButton(onClick={followLatest()},modifier=Modifier.align(Alignment.BottomEnd).padding(end=24.dp,bottom=108.dp)) {AppIcon("live",modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("回到最新"+classroomUnseenPages(count,seenCount).takeIf {it>0}?.let {" · +$it"}.orEmpty())}
            }
            if(!fullscreen && state.exportState!=null) Box(Modifier.align(Alignment.BottomCenter)) {ExportFeedback(state.exportState,model,session.materialId ?: "classroom")}
            SnackbarHost(snackbar,Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
        if(!fullscreen) Surface {
            Column(Modifier.navigationBarsPadding().padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={goTo(current-1)},enabled=current>0) {AppIcon("previous","上一页")}
                    TextButton(onClick={following=false;panel="pages"},enabled=count>0) {Text(if(count>0) "$currentPage / ${material?.pages?.size ?: count}" else "0 / 0")}
                    IconButton(onClick={goTo(current+1)},enabled=current<count-1) {AppIcon("next","下一页")}
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick={if(following) following=false else followLatest()},enabled=count>0,contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
                        AppIcon(if(following) "live" else "next",modifier=Modifier.size(17.dp));Spacer(Modifier.width(6.dp))
                        val unseen=classroomUnseenPages(count,seenCount)
                        Text(if(following) "正在跟随" else if(unseen>0) "最新 +$unseen" else "回到最新",style=MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                Row(Modifier.fillMaxWidth().heightIn(min=62.dp)) {
                    ClassroomAction("grid","总览",count>0,Modifier.weight(1f)) {following=false;panel="pages"}
                    ClassroomAction("edit",if(material?.pageNotes?.containsKey(currentPage)==true) "有批注" else "批注",count>0,Modifier.weight(1f)) {following=false;panel="note"}
                    ClassroomAction("image","保存当页",count>0,Modifier.weight(1f)) {
                        if(material!=null) {imageSavePage=currentPage;if(Build.VERSION.SDK_INT>=29) model.savePageImage(material.id,currentPage) else saveLocation.launch("${material.title}_第${currentPage}页.png")}
                    }
                    ClassroomAction("export","导出 PDF",count>0,Modifier.weight(1f)) {exportSnapshot=material?.copy(lastPage=currentPage)}
                    ClassroomAction("fullscreen","全屏",count>0,Modifier.weight(1f)) {setFull(true)}
                }
            }
        }
    }
    if(panel=="note" && material!=null && count>0) PageNoteSheet(material,currentPage,model) {panel=null}
    if(panel=="pages" && material!=null) ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxHeight(.8f)) {
            Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {Text("已保存 $count 页",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={followLatest();panel=null}) {Text("回到最新")}}
            val grid=rememberLazyGridState()
            LaunchedEffect(Unit) {if(count>0) grid.scrollToItem(current)}
            LazyVerticalGrid(GridCells.Adaptive(96.dp),state=grid,contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                items(count,key={pages[it].source}) {index ->
                    Column(Modifier.clickable {goTo(index);panel=null},horizontalAlignment=Alignment.CenterHorizontally) {
                        Surface(shape=RoundedCornerShape(8.dp),border=BorderStroke(if(index==current) 2.dp else 1.dp,if(index==current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            PageImage(mediaPath(pages[index].preview),360,Modifier.fillMaxWidth().aspectRatio(1.4f).padding(4.dp))
                        }
                        Text("${originalIndices[index]+1}"+if(material.pageNotes[originalIndices[index]+1].isNullOrBlank()) "" else " · 批注",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=6.dp))
                    }
                }
            }
        }
    }
    if(panel=="settings") ModalBottomSheet(onDismissRequest={panel=null}) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SheetHeading("课堂跟看",{panel=null})
            Text("检查频率",style=MaterialTheme.typography.labelLarge)
            PillTabs(listOf("3 秒","5 秒","10 秒"),listOf(3,5,10).indexOf(session.intervalSeconds).coerceAtLeast(0),{model.classroomInterval(listOf(3,5,10)[it])})
            Text("仅在此界面处于前台时检查。网络异常会降低检查频率；已获取页面自动保存在课程中。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(session.checkedAt>0) Text("上次同步 "+DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.checkedAt)),style=MaterialTheme.typography.bodySmall)
            PreferenceRow(if(session.stage==ClassroomStage.Paused) "live" else "pause",if(session.stage==ClassroomStage.Paused) "继续同步" else "暂停同步",onClick={if(session.stage==ClassroomStage.Paused) model.resumeClassroom() else model.pauseClassroom();panel=null})
            TextButton(onClick={panel=null;endConfirmation=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("结束本次跟看")}
        }
    }
    if(endConfirmation) AlertDialog(onDismissRequest={endConfirmation=false},title={Text("结束本次跟看？")},text={Text("已获取的页面和批注会保留在课程中，之后也可重新连接此课次。")},confirmButton={TextButton(onClick={model.endClassroom();endConfirmation=false;onBack()}) {Text("结束并保留")}},dismissButton={TextButton(onClick={endConfirmation=false}) {Text("继续跟看")}})
    exportSnapshot?.let {snapshot ->ExportSheet(listOf(snapshot),onDismiss={exportSnapshot=null},onSubmit={model.export(it.copy(origin=snapshot.id));exportSnapshot=null})}
}

@Composable
private fun ClassroomAction(icon: String,title: String,enabled: Boolean,modifier: Modifier,onClick: ()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=60.dp),contentPadding=PaddingValues(horizontal=2.dp,vertical=8.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            AppIcon(icon,modifier=Modifier.size(21.dp));Text(title,style=MaterialTheme.typography.labelMedium,maxLines=1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageNoteSheet(material: StudyMaterial,page: Int,model: KeyeViewModel,onDismiss: ()->Unit) {
    val initial=remember(material.id,page) {material.pageNotes[page].orEmpty()}
    var text by rememberSaveable(material.id,page) {mutableStateOf(initial)}
    var saving by remember {mutableStateOf(false)}
    var discard by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun close() {if(!saving) {if(text!=initial) discard=true else onDismiss()}}
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={target ->
        if(target==SheetValue.Hidden && (saving || text!=initial)) {if(!saving) discard=true;false} else true
    })
    ModalBottomSheet(onDismissRequest={close()},sheetState=sheet) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SheetHeading("第 $page 页批注",{close()})
            OutlinedTextField(text,{text=it.take(2000)},label={Text("记录这页的重点")},minLines=4,maxLines=8,enabled=!saving,modifier=Modifier.fillMaxWidth(),supportingText={Text("${text.length} / 2000 · 按页保存，不修改原图")})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            Text("保存图片会附上批注；导出 PDF 时可选择是否包含。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={saving=true;scope.launch {try {model.savePageNote(material.id,page,text);onDismiss()} catch(cancel: CancellationException) {throw cancel} catch(problem: Exception) {error=problem.message ?: "保存失败，请重试"} finally {saving=false}}},enabled=!saving,modifier=Modifier.fillMaxWidth()) {Text(if(saving) "保存中" else "保存批注")}
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存的批注？")},confirmButton={TextButton(onClick={discard=false;onDismiss()}) {Text("放弃")}},dismissButton={TextButton(onClick={discard=false}) {Text("继续编辑")}})
}
