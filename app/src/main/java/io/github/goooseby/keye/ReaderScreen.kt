package io.github.goooseby.keye

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class,FlowPreview::class)
@Composable
fun ReaderScreen(material: StudyMaterial,model: KeyeViewModel,mediaPath: (String) -> File,onBack: () -> Unit,onExport: () -> Unit,onShare: () -> Unit,exportState: ExportState?,onClassroom: ((ScheduleEntry)->Unit)?=null) {
    if(material.pages.isEmpty()) { EmptyState("error","课件没有可读取的页面",action="返回",onAction=onBack);return }
    val context=LocalContext.current
    val activity=context as Activity
    val prefs=remember {context.getSharedPreferences("reading",Context.MODE_PRIVATE)}
    val visibleIndices=remember(material.pages,material.excluded) {retainedPageIndices(material.pages.size,material.excluded)}
    if(visibleIndices.isEmpty()) {
        EmptyState("document","没有可阅读页面",action="恢复全部页面",onAction={model.setExcluded(material.id,emptySet())})
        return
    }
    var mode by rememberSaveable {mutableStateOf(prefs.getString("readingMode","continuous")?:"continuous")}
    var fullscreen by rememberSaveable {mutableStateOf(false)}
    var chrome by rememberSaveable {mutableStateOf(true)}
    var panel by remember {mutableStateOf<String?>(null)}
    var keepAwake by remember {mutableStateOf(prefs.getBoolean("awake",false))}
    var readingBrightness by rememberSaveable {mutableFloatStateOf(-1f)}
    val dark=isSystemInDarkTheme()
    var background by rememberSaveable {mutableIntStateOf(prefs.getInt("background",if(dark) 3 else 0))}
    var zoomed by remember {mutableStateOf(false)}
    var resetToken by remember {mutableIntStateOf(0)}
    var controlInteraction by remember {mutableIntStateOf(0)}
    var scrubbing by remember {mutableStateOf(false)}
    var targetOnSwitch by rememberSaveable {mutableStateOf<Int?>(null)}
    var pendingOffset by rememberSaveable {mutableIntStateOf(0)}
    var normalAnchor by rememberSaveable {mutableIntStateOf(-1)}
    var normalOffset by rememberSaveable {mutableIntStateOf(0)}
    var undoPages by remember {mutableStateOf<Set<Int>?>(null)}
    var removalCandidate by remember {mutableStateOf<Set<Int>?>(null)}
    var selectingPages by remember {mutableStateOf(false)}
    var selectedPages by remember {mutableStateOf(emptySet<Int>())}
    var menu by remember {mutableStateOf(false)}
    var shareMenu by remember {mutableStateOf(false)}
    val start=visiblePositionForPage(visibleIndices,material.lastPage-1)
    val effectiveMode=if(fullscreen) "pages" else mode
    val list=rememberLazyListState(start,if(material.readFraction>0) 0 else material.readOffset)
    var restored by rememberSaveable {mutableStateOf(false)}
    val pager=rememberPagerState(initialPage=start,pageCount={visibleIndices.size})
    val current by remember(visibleIndices,effectiveMode) {derivedStateOf {visibleIndices[(if(effectiveMode=="continuous") list.firstVisibleItemIndex else pager.currentPage).coerceIn(visibleIndices.indices)]}}
    val scope=rememberCoroutineScope()
    val saveSnackbar=remember {SnackbarHostState()}
    LaunchedEffect(exportState?.result?.uri,exportState?.failed) {
        if(fullscreen && exportState?.result?.mime=="image/png") saveSnackbar.showSnackbar("当前页已保存到 ${exportState.result.location}")
        else if(fullscreen && exportState?.failed==true) saveSnackbar.showSnackbar(exportState.message)
    }
    var imageSavePage by rememberSaveable {mutableIntStateOf(1)}
    val saveLocation=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {uri ->
        if(uri!=null) model.savePageImage(material.id,imageSavePage,uri)
    }
    fun saveCurrentPage() {
        imageSavePage=current+1;controlInteraction++
        if(Build.VERSION.SDK_INT>=29) model.savePageImage(material.id,imageSavePage)
        else saveLocation.launch("${material.title}_第${imageSavePage}页.png")
    }
    val colors=listOf(Color(0xFFF0F3F8),Color(0xFFE5F1EA),Color(0xFFFFF0D9),Color(0xFF182028))
    val stage=if(fullscreen) Color.Black else colors[background.coerceIn(colors.indices)]
    fun jump(original: Int) {
        val position=visibleIndices.indexOf(original)
        if(position<0) return
        scope.launch {zoomed=false;if(effectiveMode=="continuous") list.scrollToItem(position) else pager.scrollToPage(position)}
    }
    fun stepPage(delta: Int) {
        val target=(pager.targetPage+delta).coerceIn(visibleIndices.indices)
        controlInteraction++;resetToken++
        scope.launch {pager.animateScrollToPage(target)}
    }
    fun changeMode(value: String) {
        targetOnSwitch=current;pendingOffset=0
        zoomed=false;mode=value
        prefs.edit().putString("readingMode",value).apply()
    }
    fun setFull(value: Boolean) {
        if(value) {normalAnchor=current;normalOffset=if(mode=="continuous") list.firstVisibleItemScrollOffset else 0}
        targetOnSwitch=current
        pendingOffset=if(!value && current==normalAnchor && mode=="continuous") normalOffset else 0
        zoomed=false;resetToken++;panel=null;menu=false;shareMenu=false;fullscreen=value;chrome=true;controlInteraction++
    }
    fun stageRemoval(pages: Set<Int>) {
        if(pages.isNotEmpty() && visibleIndices.count {it+1 !in pages}>0) removalCandidate=pages
    }
    fun rememberAnchorAfterRemoval(pages: Set<Int>) {
        val remaining=visibleIndices.filter {it+1 !in pages}
        targetOnSwitch=remaining.firstOrNull {it>=current} ?: remaining.last()
        pendingOffset=0
    }
    BackHandler {if(fullscreen) setFull(false) else onBack()}
    DisposableEffect(fullscreen) {
        activity.requestedOrientation=if(fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if(!activity.isChangingConfigurations) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
    DisposableEffect(readingBrightness) {
        val previous=activity.window.attributes.screenBrightness
        activity.window.attributes=activity.window.attributes.apply {screenBrightness=readingBrightness}
        onDispose {activity.window.attributes=activity.window.attributes.apply {screenBrightness=previous}}
    }
    DisposableEffect(keepAwake,fullscreen) {
        if(keepAwake||fullscreen) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    LaunchedEffect(fullscreen,chrome,panel,menu,shareMenu,removalCandidate,controlInteraction,scrubbing) {
        val accessibility=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if(fullscreen&&chrome&&!scrubbing&&panel==null&&!menu&&!shareMenu&&removalCandidate==null&&accessibility?.isTouchExplorationEnabled!=true) {delay(3000);chrome=false}
    }
    LaunchedEffect(Unit) {
        if(restored) return@LaunchedEffect
        if(effectiveMode=="continuous" && material.readFraction>0) {
            val height=snapshotFlow {list.layoutInfo.visibleItemsInfo.firstOrNull {it.index==start}?.size ?: 0}.first {it>0}
            list.scrollToItem(start,(height*material.readFraction).toInt())
        }
        restored=true
    }
    LaunchedEffect(effectiveMode,visibleIndices) {
        snapshotFlow {restored}.first {it}
        targetOnSwitch?.let {original ->
            val position=visiblePositionForPage(visibleIndices,original)
            if(effectiveMode=="continuous") list.scrollToItem(position,pendingOffset) else pager.scrollToPage(position)
            targetOnSwitch=null;pendingOffset=0
        }
        if(effectiveMode=="continuous") snapshotFlow {Triple(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,list.isScrollInProgress)}
            .filter {!it.third && targetOnSwitch==null}.debounce(250).distinctUntilChanged().collect {
                val original=visibleIndices.getOrNull(it.first) ?: return@collect
                val height=list.layoutInfo.visibleItemsInfo.firstOrNull {item ->item.index==it.first}?.size ?: 1
                model.lastPage(material.id,original+1,it.second,it.second.toFloat()/height.coerceAtLeast(1))
            }
        else snapshotFlow {pager.settledPage}.filter {targetOnSwitch==null}.distinctUntilChanged().collect {position ->
            visibleIndices.getOrNull(position)?.let {model.lastPage(material.id,it+1,0)}
        }
    }
    Column(Modifier.fillMaxSize().background(stage)) {
        if(!fullscreen) AnimatedVisibility(chrome,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()) {
            Surface(color=MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().then(if(!fullscreen) Modifier.statusBarsPadding() else Modifier).height(56.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={if(fullscreen) setFull(false) else onBack()}){AppIcon("back","返回")}
                    Column(Modifier.weight(1f)) {
                        Text(material.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${material.day} · ${visibleIndices.size} 页",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick={shareMenu=true}){AppIcon("share","分享课件",Modifier.size(21.dp))}
                        DropdownMenu(shareMenu,{shareMenu=false}) {
                            DropdownMenuItem(text={Text("分享 PDF")},leadingIcon={AppIcon("document")},onClick={shareMenu=false;onShare()})
                            DropdownMenuItem(text={Text("分享当前页图片")},leadingIcon={AppIcon("image")},onClick={shareMenu=false;model.sharePage(material.id,current+1)})
                        }
                    }
                    Box {
                        IconButton(onClick={menu=true}){AppIcon("more","阅读工具")}
                        DropdownMenu(menu,{menu=false}) {
                            material.schoolEntry?.let {entry ->if(onClassroom!=null) DropdownMenuItem(text={Text("课堂跟看")},leadingIcon={AppIcon("live")},onClick={menu=false;onClassroom(entry)})}
                            DropdownMenuItem(text={Text("阅读工具")},leadingIcon={AppIcon("tools")},onClick={menu=false;panel="tools"})
                            DropdownMenuItem(text={Text("导出 PDF")},leadingIcon={AppIcon("export")},onClick={menu=false;onExport()})
                            DropdownMenuItem(text={Text(if(current+1 in material.bookmarks) "移除本页书签" else "添加本页书签")},leadingIcon={AppIcon("bookmark")},onClick={menu=false;model.bookmark(material.id,current+1)})
                            DropdownMenuItem(text={Text("移除当前页")},leadingIcon={AppIcon("trash")},enabled=visibleIndices.size>1,onClick={menu=false;stageRemoval(setOf(current+1))})
                            DropdownMenuItem(text={Text("课件信息")},leadingIcon={AppIcon("info")},onClick={menu=false;panel="info"})
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(effectiveMode=="continuous") ContinuousReader(material,visibleIndices,list,mediaPath,resetToken,
                onTap={chrome=!chrome},onZoomed={zoomed=it}) else HorizontalPager(pager,userScrollEnabled=!zoomed,modifier=Modifier.fillMaxSize(),beyondViewportPageCount=1,key={visibleIndices[it]}) {position ->
                val original=visibleIndices[position]
                ReaderCanvas(material.pages[original],mediaPath,Modifier.fillMaxSize(),pageNumber=original+1,continuous=false,active=original==current,resetToken=resetToken,onTap={chrome=!chrome},onZoomed={if(original==current) zoomed=it})
            }
            if(!fullscreen&&!chrome) Surface(onClick={panel="pages"},shape=RoundedCornerShape(30.dp),color=Color.Black.copy(alpha=.5f),contentColor=Color.White,modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Text("${current+1} / ${material.pages.size}",Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall)
            }
            if(fullscreen) FullscreenReaderControls(
                visible=chrome, title=material.title, position=visibleIndices.indexOf(current),
                originalPage=current+1, totalPages=material.pages.size, retainedCount=visibleIndices.size,
                bookmarked=current+1 in material.bookmarks, zoomed=zoomed,
                onExit={setFull(false)}, onPrevious={stepPage(-1)}, onNext={stepPage(1)},
                onSeek={controlInteraction++;resetToken++;jump(visibleIndices[it])}, onScrubbing={scrubbing=it;controlInteraction++},
                onBookmark={controlInteraction++;model.bookmark(material.id,current+1)},
                onReset={controlInteraction++;resetToken++},onSavePage={saveCurrentPage()},saveBusy=exportState?.running==true)
            if(fullscreen) SnackbarHost(saveSnackbar,Modifier.align(Alignment.TopCenter).padding(top=64.dp))
            if(!fullscreen) Column(Modifier.align(Alignment.BottomCenter)) {
                undoPages?.let {previous ->
                    Snackbar(Modifier.padding(12.dp),action={TextButton(onClick={targetOnSwitch=current;model.setExcluded(material.id,previous);undoPages=null}) {Text("撤销")}},dismissAction={IconButton(onClick={undoPages=null}) {AppIcon("close","关闭页面移除提示")}}) {Text("已移除页面，可在总览中恢复")}
                }
                if(exportState!=null) ExportFeedback(exportState,model,material.id)
            }
        }
        if(!fullscreen) AnimatedVisibility(chrome,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()) {
            Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {
                Row(Modifier.fillMaxWidth().then(if(!fullscreen) Modifier.navigationBarsPadding() else Modifier).padding(horizontal=12.dp).height(56.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={panel="pages"}){AppIcon("grid","页面总览",Modifier.size(21.dp))}
                    TextButton(onClick={panel="jump"}) {Text("${current+1} / ${material.pages.size}")}
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={model.bookmark(material.id,current+1)}) {CompositionLocalProvider(LocalContentColor provides if(current+1 in material.bookmarks) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant){AppIcon("bookmark",if(current+1 in material.bookmarks) "移除书签" else "添加书签",Modifier.size(21.dp))}}
                    IconButton(onClick={panel="tools"}) {AppIcon("tools","阅读工具",Modifier.size(21.dp))}
                    IconButton(onClick={setFull(!fullscreen)}) {AppIcon("fullscreen",if(fullscreen) "退出全屏" else "全屏阅读",Modifier.size(21.dp))}
                }
            }
        }
    }
    if(panel=="note") PageNoteSheet(material,current+1,model) {panel=null}
    if(panel=="tools") ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SheetHeading("阅读工具",{panel=null})
            ReaderToolGroup("阅读",listOf(
                ReaderTool("settings","查看设置") {panel="view"},
                ReaderTool("edit","本页批注") {panel="note"},
                ReaderTool("fit","还原缩放") {resetToken++;panel=null},
                ReaderTool("first","回到首页") {resetToken++;jump(visibleIndices.first());panel=null},
                ReaderTool("last","前往末页") {resetToken++;jump(visibleIndices.last());panel=null}))
            val nextBookmark=visibleIndices.firstOrNull {it>current && it+1 in material.bookmarks}
                ?: visibleIndices.firstOrNull {it!=current && it+1 in material.bookmarks}
            ReaderToolGroup("页面",listOf(
                ReaderTool("grid","页面总览") {panel="pages"},
                ReaderTool("bookmark","下一书签",nextBookmark!=null) {nextBookmark?.let {jump(it)};panel=null},
                ReaderTool("trash","移除本页",visibleIndices.size>1) {panel=null;stageRemoval(setOf(current+1))},
                ReaderTool("info","课件信息") {panel="info"}))
            ReaderToolGroup("导出与分享",listOf(
                ReaderTool("export","导出 PDF") {panel=null;onExport()},
                ReaderTool("share","分享 PDF") {panel=null;onShare()},
                ReaderTool("download","保存本页") {panel=null;saveCurrentPage()},
                ReaderTool("image","分享本页") {panel=null;model.sharePage(material.id,current+1)}))
        }
    }
    if(panel=="pages") ModalBottomSheet(onDismissRequest={panel=null;selectingPages=false;selectedPages=emptySet()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        var filter by remember {mutableIntStateOf(0)}
        val indices=when(filter) {1 ->visibleIndices.filter {it+1 in material.bookmarks};2 ->material.pages.indices.filter {it+1 in material.excluded};else ->visibleIndices}
        Column(Modifier.fillMaxHeight(.88f)) {
            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(selectingPages) "已选 ${selectedPages.size} 页" else "页面总览",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={selectingPages=!selectingPages;selectedPages=emptySet()}) {Text(if(selectingPages) "完成" else "选择")}
                IconButton(onClick={panel=null;selectingPages=false;selectedPages=emptySet()}) {AppIcon("close","关闭页面总览")}
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(filter==0,{filter=0;selectedPages=emptySet()},label={Text("页面 ${visibleIndices.size}")})
                FilterChip(filter==1,{filter=1;selectedPages=emptySet()},label={Text("书签 ${material.bookmarks.size}")})
                FilterChip(filter==2,{filter=2;selectedPages=emptySet()},label={Text("已移除 ${material.excluded.size}")})
            }
            val grid=rememberLazyGridState()
            LaunchedEffect(filter) {
                val position=indices.indexOf(current).coerceAtLeast(0)
                delay(300);grid.scrollToItem(position)
            }
            LazyVerticalGrid(columns=GridCells.Adaptive(90.dp),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(indices.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {EmptyHint(if(filter==2) "没有移除的页面" else "还没有页书签")}
                items(indices,key={it}) {original ->
                    val page=original+1
                    val chosen=page in selectedPages
                    Column(Modifier.combinedClickable(onClick={
                        if(selectingPages) selectedPages=if(chosen) selectedPages-page else selectedPages+page
                        else if(filter==2) {targetOnSwitch=current;model.restorePages(material.id,setOf(page))}
                        else {jump(original);panel=null}
                    },onLongClick={selectingPages=true;selectedPages=selectedPages+page}),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Surface(shape=RoundedCornerShape(8.dp),border=BorderStroke(if(chosen||original==current) 2.dp else 1.dp,if(chosen||original==current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.25f).padding(4.dp)) {
                                PageImage(mediaPath(material.pages[original].preview),360,Modifier.fillMaxSize().graphicsLayer {alpha=if(filter==2) .45f else 1f})
                                if(selectingPages) Checkbox(chosen,{selectedPages=if(it) selectedPages+page else selectedPages-page},Modifier.align(Alignment.TopStart))
                                else if(page in material.bookmarks) AppIcon("bookmark",modifier=Modifier.align(Alignment.TopEnd).size(16.dp))
                            }
                        }
                        Text(if(filter==2&&!selectingPages) "$page · 点按恢复" else "$page",style=MaterialTheme.typography.labelMedium,color=if(original==current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(selectingPages) Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={selectedPages=if(selectedPages.containsAll(indices.map {it+1})) emptySet() else indices.map {it+1}.toSet()},enabled=indices.isNotEmpty()) {Text("全选")}
                    Spacer(Modifier.weight(1f))
                    if(filter==2) Button(onClick={targetOnSwitch=current;model.restorePages(material.id,selectedPages);selectedPages=emptySet();selectingPages=false},enabled=selectedPages.isNotEmpty()) {Text("恢复所选")}
                    else Button(onClick={stageRemoval(selectedPages)},enabled=selectedPages.isNotEmpty()&&visibleIndices.size>selectedPages.size) {Text("移除所选")}
                }
                if(filter!=2&&selectedPages.size==visibleIndices.size) Text("请至少保留一页",Modifier.padding(horizontal=20.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    removalCandidate?.let {pages ->
        AlertDialog(onDismissRequest={removalCandidate=null},title={Text(if(pages.size==1) "移除这一页？" else "移除 ${pages.size} 页？")},text={Text("将从阅读和默认导出中移除这些页面。原件保留，可在页面总览的“已移除”中恢复。")},
            confirmButton={TextButton(onClick={
                undoPages=material.excluded;rememberAnchorAfterRemoval(pages);model.removePages(material.id,pages)
                removalCandidate=null;selectedPages=emptySet();selectingPages=false;panel=null
            }) {Text("移除")}},dismissButton={TextButton(onClick={removalCandidate=null}) {Text("取消")}})
    }
    if(panel=="view") ModalBottomSheet(onDismissRequest={panel=null}) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SheetHeading("查看设置",{panel=null})
            Text("阅读方式",style=MaterialTheme.typography.labelLarge)
            if(fullscreen) Text("全屏采用横向逐页；退出后恢复常规阅读方式",style=MaterialTheme.typography.bodyMedium)
            else PillTabs(listOf("连续阅读","逐页阅读"),if(mode=="continuous") 0 else 1,{changeMode(if(it==0) "continuous" else "pages")})
            Text("阅读背景",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                colors.forEachIndexed {index,color ->
                    Surface(onClick={background=index;prefs.edit().putInt("background",index).apply()},color=color,shape=RoundedCornerShape(30.dp),border=BorderStroke(if(background==index) 2.dp else 1.dp,if(background==index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.size(48.dp)) {
                        Box(contentAlignment=Alignment.Center) {if(background==index) CompositionLocalProvider(LocalContentColor provides if(index==3) Color.White else MaterialTheme.colorScheme.primary) {AppIcon("check","已选背景",Modifier.size(20.dp))}}
                    }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically) {Text("阅读时保持亮屏",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(keepAwake,{keepAwake=it;prefs.edit().putBoolean("awake",it).apply()})}
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("跟随系统亮度",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(readingBrightness<0,{readingBrightness=if(it) -1f else .5f})
            }
            if(readingBrightness>=0) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    AppIcon("brightness","阅读亮度")
                    Slider(readingBrightness,{readingBrightness=it},valueRange=.05f..1f,modifier=Modifier.weight(1f))
                    Text("${(readingBrightness*100).toInt()}%",style=MaterialTheme.typography.labelMedium)
                }
            }
            Text("背景仅影响页面周围，不改变课件颜色；亮度仅在阅读器内生效",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(panel=="jump") {
        var target by remember {mutableStateOf((current+1).toString())}
        val number=target.toIntOrNull()
        val available=number!=null && number-1 in visibleIndices
        AlertDialog(onDismissRequest={panel=null},title={Text("跳转到页面")},text={OutlinedTextField(target,{target=it.filter(Char::isDigit).take(6)},label={Text("原始页码 · 1–${material.pages.size}")},supportingText={if(number!=null && number in 1..material.pages.size && !available) Text("该页已移除，可在总览恢复")},isError=number!=null&&!available,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))},confirmButton={TextButton(onClick={jump(number!!-1);panel=null},enabled=available){Text("跳转")}},dismissButton={TextButton(onClick={panel=null}){Text("取消")}})
    }
    if(panel=="info") ModalBottomSheet(onDismissRequest={panel=null}) {
        Column(Modifier.padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SheetHeading("课件信息",{panel=null})
            Text(material.title,style=MaterialTheme.typography.titleMedium)
            Text("${material.day} · ${material.pages.size} 页 · ${if(material.kind=="pdf") "PDF" else "图片课件"}",style=MaterialTheme.typography.bodyMedium)
            if(material.note.isNotBlank()) Text(material.note,style=MaterialTheme.typography.bodyMedium)
            PreferenceRow("edit","名称与备注",onClick={panel="edit"})
            PreferenceRow("folder","所属课程",onClick={panel="course"})
            Button(onClick={panel=null;onExport()},modifier=Modifier.fillMaxWidth()){Text("导出 PDF")}
            TextButton(onClick={panel="delete"},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("移入回收站")}
        }
    }
    if(panel=="edit") {
        var name by remember {mutableStateOf(material.title)}
        var note by remember {mutableStateOf(material.note)}
        ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SheetHeading("名称与备注",{panel=null})
                OutlinedTextField(name,{name=it.take(120)},label={Text("课件名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
                OutlinedTextField(note,{note=it.take(2000)},label={Text("备注")},minLines=3,maxLines=5,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
                Button(onClick={model.updateMaterial(material.id,title=name,note=note);panel=null},enabled=name.isNotBlank(),modifier=Modifier.fillMaxWidth().height(50.dp)){Text("保存")}
            }
        }
    }
    if(panel=="course") {
        val library by model.state.collectAsState()
        ModalBottomSheet(onDismissRequest={panel=null}) {
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp)) {
                SheetHeading("所属课程",{panel=null})
                PreferenceRow("folder","未归类",value=if(material.courseId==null) "当前" else null,onClick={model.assignCourse(material.id,null);panel=null})
                library.courses.forEach {course ->PreferenceRow("library",course.title,value=if(material.courseId==course.id) "当前" else null,onClick={model.assignCourse(material.id,course.id);panel=null})}
            }
        }
    }
    if(panel=="delete") AlertDialog(onDismissRequest={panel=null},title={Text("移入回收站？")},text={Text("课件可以在“我的”的回收站中恢复。")},confirmButton={TextButton(onClick={model.setDeleted(material.id,true);panel=null;onBack()}){Text("移入")}},dismissButton={TextButton(onClick={panel=null}){Text("取消")}})
}

private data class ReaderBitmap(val bitmap: Bitmap?=null,val finished: Boolean=false)
private val readerCache=object:android.util.LruCache<String,Bitmap>(40*1024*1024) {override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}

@Composable
internal fun ReaderCanvas(page: PageFile,mediaPath: (String) -> File,modifier: Modifier,pageNumber: Int,continuous: Boolean,active: Boolean,resetToken: Int,onTap: () -> Unit,onZoomed: (Boolean) -> Unit,gesturesEnabled: Boolean=true,highResolution: Boolean=false) {
    var scale by remember(page.source,page.pdfPage) {mutableFloatStateOf(1f)}
    var offset by remember(page.source,page.pdfPage) {mutableStateOf(Offset.Zero)}
    var bounds by remember {mutableStateOf(IntSize.Zero)}
    val source=mediaPath(page.source)
    val maxSize=if(highResolution || scale>1.01f) 3000 else 1800
    val loaded by produceState(ReaderBitmap(),source.path,page.pdfPage,maxSize) {
        val key="${source.path}:${source.lastModified()}:${page.pdfPage}:$maxSize"
        val bitmap=withContext(Dispatchers.IO) {
            readerCache.get(key) ?: runCatching {
                renderReadingPage(source,page.pdfPage,maxSize)
            }.getOrNull()?.also {readerCache.put(key,it)}
        }
        value=ReaderBitmap(bitmap,true)
    }
    LaunchedEffect(resetToken) {scale=1f;offset=Offset.Zero}
    LaunchedEffect(active) {if(!active){scale=1f;offset=Offset.Zero}}
    LaunchedEffect(scale,active) {if(active) onZoomed(scale>1.01f)}
    val currentTap by rememberUpdatedState(onTap)
    val transform=rememberTransformableState {zoom,pan,_ ->
        val old=scale;scale=(scale*zoom).coerceIn(1f,5f)
        val next=offset*(scale/old)+pan
        offset=Offset(next.x.coerceIn(-bounds.width*(scale-1)/2,bounds.width*(scale-1)/2),next.y.coerceIn(-bounds.height*(scale-1)/2,bounds.height*(scale-1)/2))
    }
    val bitmap=loaded.bitmap
    val previewAspect=remember(page.preview) {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(mediaPath(page.preview).path,bounds)
        if(bounds.outWidth>0 && bounds.outHeight>0) bounds.outWidth.toFloat()/bounds.outHeight else 16f/9f
    }
    val aspect=bitmap?.let {it.width.toFloat()/it.height} ?: previewAspect
    Box(modifier.then(if(continuous) Modifier.aspectRatio(aspect) else Modifier).clipToBounds().onSizeChanged {bounds=it}
        .then(if(gesturesEnabled) Modifier.transformable(transform,canPan={scale>1.01f}) else Modifier)
        .then(if(gesturesEnabled) Modifier.pointerInput(page.source,page.pdfPage) {detectTapGestures(onTap={currentTap()},onDoubleTap={point ->
            scale=if(scale>1.01f) 1f else 2.5f
            offset=if(scale==1f) Offset.Zero else Offset((bounds.width/2-point.x)*(scale-1),(bounds.height/2-point.y)*(scale-1))
        })} else Modifier),contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap.asImageBitmap(),"第${pageNumber}页课件",Modifier.fillMaxSize().graphicsLayer {scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y},contentScale=ContentScale.Fit)
        else if(!loaded.finished) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        else Text("页面无法读取",color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp))
    }
}
