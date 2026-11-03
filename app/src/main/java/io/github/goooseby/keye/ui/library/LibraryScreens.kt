package io.github.goooseby.keye.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.app.KeyeViewModel
import io.github.goooseby.keye.app.UiState
import io.github.goooseby.keye.data.model.Course
import io.github.goooseby.keye.data.model.StudyMaterial
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import io.github.goooseby.keye.ui.components.IconBadge
import io.github.goooseby.keye.ui.components.PageImage
import io.github.goooseby.keye.ui.components.PillTabs
import io.github.goooseby.keye.ui.components.SearchField
import io.github.goooseby.keye.ui.components.SheetHeading
import java.io.File
import kotlinx.coroutines.delay

@Composable
private fun courseTone(key: String): Pair<Color, Color> {
    if(isSystemInDarkTheme()) return when(key.hashCode().mod(4)) {
        0 -> Color(0xFF203C4B) to Color(0xFF8ED8FF)
        1 -> Color(0xFF352C4A) to Color(0xFFCEBAFF)
        2 -> Color(0xFF3E3526) to Color(0xFFD9C39C)
        else -> Color(0xFF203F35) to Color(0xFF89DDB6)
    }
    return when(key.hashCode().mod(4)) {
        0 -> Color(0xFFE2F3FD) to Color(0xFF126A92)
        1 -> Color(0xFFEEE7FC) to Color(0xFF7552AC)
        2 -> Color(0xFFFFF0D6) to Color(0xFF91641E)
        else -> Color(0xFFE1F4ED) to Color(0xFF237554)
    }
}

@Composable
fun LibraryScreen(state: UiState, onCourse: (String?) -> Unit, onMaterial: (String) -> Unit,
    onImport: () -> Unit, onExport: (String, String) -> Unit, onBatchExport: (List<String>, String) -> Unit,
    mediaPath: (String) -> File, model: KeyeViewModel, initialTab: Int=1) {
    var search by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var newCourse by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    BackHandler(selected.isNotEmpty()) { selected=emptySet() }
    val largeType=LocalDensity.current.fontScale>1.2f
    val active = state.materials.filterNot(StudyMaterial::deleted)
    val listed = state.materials.filter { it.deleted==(tab==2) && (it.title.contains(search,true)||it.note.contains(search,true)) }
        .sortedWith(compareByDescending<StudyMaterial>{it.readAt}.thenByDescending {it.touched})
    val chosen = active.filter { it.id in selected }
    val matchedCourses=state.courses.filter{it.title.contains(search,true)||it.term.contains(search,true)}.sortedBy(Course::title)
    Box(Modifier.fillMaxSize()) {
        Column {
            AnimatedContent(selected.isNotEmpty(), transitionSpec={fadeIn(tween(150)) togetherWith fadeOut(tween(100))}, label="selection header") { selecting ->
                if(selecting) Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=12.dp), verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={selected=emptySet()}) {AppIcon("close", "结束选择")}
                    Text("已选 ${chosen.size} 份", style=MaterialTheme.typography.titleMedium)
                } else PillTabs(if(tab==2) listOf("最近阅读","全部课程","回收站") else listOf("最近阅读","全部课程"), if(tab==0) 1 else if(tab==1) 0 else 2, {tab=if(it==0) 1 else if(it==1) 0 else 2;search=""}, Modifier.padding(horizontal=20.dp, vertical=4.dp))
            }
            LazyVerticalGrid(columns=if(largeType) GridCells.Fixed(1) else GridCells.Adaptive(148.dp), modifier=Modifier.weight(1f),
                contentPadding=PaddingValues(start=20.dp,end=20.dp,top=16.dp,bottom=112.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item(span={GridItemSpan(maxLineSpan)}, key="search") { SearchField(search,{search=it},if(tab==0) "搜索课程" else "搜索课件或备注") }
                if(tab==0) {
                    item(span={GridItemSpan(maxLineSpan)}, key="courses heading") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Text("课程 · ${state.courses.size}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick={newCourse=true}) {AppIcon("add",modifier=Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新建")}
                        }
                    }
                    items(matchedCourses,key=Course::id,span={GridItemSpan(if(matchedCourses.size==1) maxLineSpan else 1)}) { course ->
                        CourseTile(course,active.count {it.courseId==course.id},Modifier.animateItem(),wide=matchedCourses.size==1||largeType) {onCourse(course.id)}
                    }
                    if(search.isBlank()&&active.any{it.courseId==null}) item(span={GridItemSpan(maxLineSpan)},key="unfiled") {
                        Surface(onClick={onCourse(null)},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                IconBadge("folder",background=MaterialTheme.colorScheme.surfaceVariant,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {Text("未归类",style=MaterialTheme.typography.titleMedium);Text("${active.count {it.courseId==null}} 份课件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                AppIcon("next",modifier=Modifier.size(18.dp))
                            }
                        }
                    }
                    if(state.courses.isEmpty() && search.isBlank()) item(span={GridItemSpan(maxLineSpan)}) {
                        EmptyState("library","还没有课程",action="新建课程",onAction={newCourse=true})
                    }
                    if(search.isNotBlank() && state.courses.none {it.title.contains(search,true)||it.term.contains(search,true)}) item(span={GridItemSpan(maxLineSpan)}) {EmptyState("search","没有找到课程",detail="试试其他关键词")}
                } else {
                    item(span={GridItemSpan(maxLineSpan)}) {Text(if(tab==2) "${listed.size} 份已移除的课件" else if(listed.any{it.readAt>0}) "继续阅读" else "最近加入",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=4.dp))}
                    if(listed.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {EmptyState(if(tab==2) "trash" else "document",if(search.isNotBlank()) "没有找到课件" else if(tab==2) "回收站为空" else "暂无课件",action=if(tab==1 && search.isBlank()) "导入资料" else null,onAction=onImport)}
                    items(listed,key=StudyMaterial::id,span={GridItemSpan(maxLineSpan)}) { m ->
                        MaterialCard(m,{onMaterial(m.id)},{onExport(m.id,m.title)},{model.setDeleted(m.id,!m.deleted)},mediaPath,
                            selected=if(selected.isNotEmpty()) m.id in selected else null,
                            onSelect={selected=if(m.id in selected) selected-m.id else selected+m.id},
                            onLongPress=if(!m.deleted) ({selected=selected+m.id}) else null,modifier=Modifier.animateItem())
                    }
                }
            }
        }
        AnimatedVisibility(selected.isEmpty() && tab!=2,modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp),enter=fadeIn()+scaleIn(),exit=fadeOut()+scaleOut()) {
            ExtendedFloatingActionButton(onClick=onImport,icon={AppIcon("add")},text={Text("导入")},
                containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
                shape=RoundedCornerShape(20.dp),elevation=FloatingActionButtonDefaults.elevation(4.dp,6.dp))
        }
        AnimatedVisibility(chosen.isNotEmpty(),modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),enter=slideInVertically{it}+fadeIn(),exit=slideOutVertically{it}+fadeOut()) {
            BatchActions(chosen,state.courses,{selected=emptySet()},
                {onBatchExport(chosen.sortedWith(compareBy(StudyMaterial::day,StudyMaterial::title)).map(StudyMaterial::id),"所选课件")},
                {destination -> model.assignCourses(selected.toList(),destination);selected=emptySet()},
                {model.deleteMaterials(selected.toList());selected=emptySet()})
        }
    }
    if(newCourse) CourseDialog(null,{newCourse=false}) {title,term -> model.createCourse(title,term);newCourse=false}
}

@Composable
private fun CourseTile(course: Course,count: Int,modifier: Modifier=Modifier,wide: Boolean=false,onClick: () -> Unit) {
    val (background,accent)=courseTone(course.id)
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=background) {
        if(wide) Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            IconBadge("library",Modifier.size(54.dp),accent,Color.White.copy(alpha=if(isSystemInDarkTheme()) .08f else .7f))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(course.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(course.term.isBlank()) "$count 份课件" else "${course.term} · $count 份",style=MaterialTheme.typography.bodySmall,color=accent)
            }
            CompositionLocalProvider(LocalContentColor provides accent){AppIcon("next",modifier=Modifier.size(18.dp))}
        } else Box(Modifier.heightIn(min=174.dp)) {
            Canvas(Modifier.matchParentSize().clip(RoundedCornerShape(22.dp))) {
                drawCircle(accent.copy(alpha=.055f),size.width*.65f,Offset(size.width*1.05f,size.height*.95f))
                drawCircle(accent.copy(alpha=.06f),size.width*.5f,Offset(size.width*1.05f,size.height*.95f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconBadge("library",Modifier.size(38.dp),accent,Color.White.copy(alpha=if(isSystemInDarkTheme()) .08f else .7f))
                    Spacer(Modifier.weight(1f))
                    CompositionLocalProvider(LocalContentColor provides accent.copy(alpha=.7f)){AppIcon("next",modifier=Modifier.size(16.dp))}
                }
                Spacer(Modifier.height(8.dp))
                Text(course.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(course.term.isBlank()) "$count 份课件" else "${course.term} · $count 份",style=MaterialTheme.typography.bodySmall,color=accent,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BatchActions(materials: List<StudyMaterial>,courses: List<Course>,onClose: () -> Unit,
    onExport: () -> Unit,onAssign: (String?) -> Unit,onTrash: () -> Unit) {
    var menu by remember {mutableStateOf(false)}
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.primaryContainer,shadowElevation=8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose){AppIcon("close","结束选择")}
            Text("${materials.size} 份",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            Box {
                IconButton(onClick={menu=true}){AppIcon("move","归入课程")}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("未归类")},onClick={menu=false;onAssign(null)})
                    courses.forEach {course -> DropdownMenuItem(text={Text(course.title)},onClick={menu=false;onAssign(course.id)})}
                }
            }
            IconButton(onClick=onTrash){AppIcon("trash","移入回收站")}
            FilledIconButton(onClick=onExport,enabled=materials.isNotEmpty()&&materials.all{it.pages.isNotEmpty()}){AppIcon("export","合并导出")}
        }
    }
}

@Composable
fun CourseScreen(course: Course?,courses: List<Course>,materials: List<StudyMaterial>,onOpen: (String) -> Unit,
    onImport: () -> Unit,onExport: (String,String) -> Unit,onBatchExport: (List<String>,String) -> Unit,
    mediaPath: (String) -> File,model: KeyeViewModel) {
    var editing by remember(course?.id){mutableStateOf(false)}
    var selecting by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf(emptySet<String>())}
    var menu by remember {mutableStateOf(false)}
    BackHandler(selecting){selecting=false;selected=emptySet()}
    var ascending by rememberSaveable {mutableStateOf(false)}
    val chosen=materials.filter{it.id in selected}
    val (background,accent)=courseTone(course?.id.orEmpty())
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding=PaddingValues(20.dp,12.dp,20.dp,112.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Surface(shape=RoundedCornerShape(22.dp),color=background) {
                    Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        IconBadge("library",Modifier.size(52.dp),accent,Color.White.copy(alpha=if(isSystemInDarkTheme()) .08f else .5f))
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${materials.size} 份课件",style=MaterialTheme.typography.titleLarge)
                            Text(course?.term?.takeIf{it.isNotBlank()} ?: "共 ${materials.sumOf{it.pages.size}} 页",style=MaterialTheme.typography.bodySmall,color=accent)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(selecting) "点选课件" else "课件",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={ascending=!ascending}) {Text(if(ascending) "日期正序" else "最近课次")}
                    Box {
                        IconButton(onClick={menu=true}){AppIcon("more","课程操作")}
                        DropdownMenu(menu,{menu=false}) {
                            DropdownMenuItem(text={Text(if(selecting) "结束选择" else "选择课件")},leadingIcon={AppIcon("select")},onClick={selecting=!selecting;selected=emptySet();menu=false})
                            if(course!=null) DropdownMenuItem(text={Text("编辑课程")},leadingIcon={AppIcon("edit")},onClick={editing=true;menu=false})
                            DropdownMenuItem(text={Text("合并导出")},leadingIcon={AppIcon("export")},enabled=materials.isNotEmpty()&&materials.all{it.pages.isNotEmpty()},onClick={onBatchExport(materials.sortedWith(compareBy(StudyMaterial::day,StudyMaterial::title)).map(StudyMaterial::id),course?.title?:"未归类");menu=false})
                        }
                    }
                }
            }
            if(materials.isEmpty()) item {EmptyState("document","此课程暂无课件",action="导入资料",onAction=onImport)}
            items(if(ascending) materials.sortedBy(StudyMaterial::day) else materials.sortedByDescending(StudyMaterial::day),key=StudyMaterial::id) {m ->
                MaterialCard(m,{onOpen(m.id)},{onExport(m.id,m.title)},{model.setDeleted(m.id,true)},mediaPath,
                    selected=if(selecting) m.id in selected else null,
                    onSelect={selected=if(m.id in selected) selected-m.id else selected+m.id},
                    onLongPress={selecting=true;selected=selected+m.id},modifier=Modifier.animateItem())
            }
        }
        if(!selecting) ExtendedFloatingActionButton(onClick=onImport,icon={AppIcon("add")},text={Text("导入")},modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp),containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,shape=RoundedCornerShape(20.dp))
        AnimatedVisibility(chosen.isNotEmpty(),modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),enter=slideInVertically{it}+fadeIn(),exit=slideOutVertically{it}+fadeOut()) {
            BatchActions(chosen,courses,{selecting=false;selected=emptySet()},
                {onBatchExport(chosen.sortedWith(compareBy(StudyMaterial::day,StudyMaterial::title)).map(StudyMaterial::id),course?.title?:"所选课件")},
                {model.assignCourses(selected.toList(),it);selected=emptySet();selecting=false},
                {model.deleteMaterials(selected.toList());selected=emptySet();selecting=false})
        }
    }
    if(editing&&course!=null) CourseDialog(course,{editing=false}){title,term ->model.renameCourse(course.id,title,term);editing=false}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MaterialCard(material: StudyMaterial,onOpen: () -> Unit,onExport: () -> Unit,onTrash: () -> Unit,
    mediaPath: (String) -> File,selected: Boolean?=null,onSelect: (() -> Unit)?=null,onLongPress: (() -> Unit)?=null,
    modifier: Modifier=Modifier) {
    var menu by remember {mutableStateOf(false)}
    val haptic=LocalHapticFeedback.current
    val background by animateColorAsState(if(selected==true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,label="material selection")
    val border by animateColorAsState(if(selected==true) MaterialTheme.colorScheme.primary.copy(alpha=.4f) else MaterialTheme.colorScheme.surfaceContainer,label="material border")
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(19.dp),color=background,border=BorderStroke(1.dp,border)) {
        Row(Modifier.combinedClickable(onClick={if(selected!=null) onSelect?.invoke() else if(!material.deleted) onOpen()},onLongClick={if(onLongPress!=null){haptic.performHapticFeedback(HapticFeedbackType.LongPress);onLongPress()}})
            .padding(start=14.dp,end=6.dp,top=14.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            if(selected!=null) Checkbox(selected,{onSelect?.invoke()},modifier=Modifier.size(48.dp))
            Box(Modifier.size(54.dp,72.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                material.pages.firstOrNull()?.let{PageImage(mediaPath(it.preview),220,Modifier.fillMaxSize())}
                Surface(Modifier.align(Alignment.BottomEnd).padding(3.dp),shape=RoundedCornerShape(4.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                    Text("${material.pages.size}",Modifier.padding(horizontal=4.dp,vertical=1.dp),style=MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(material.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${if(material.kind=="pdf") "PDF" else "图片"}  ·  ${material.day}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(material.readAt>0 && !material.deleted) Text("读到 ${material.lastPage} / ${material.pages.size} 页",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            }
            if(selected==null) Box {
                IconButton(onClick={menu=true}){AppIcon("more","课件操作",Modifier.size(20.dp))}
                DropdownMenu(menu,{menu=false}) {
                    if(!material.deleted) DropdownMenuItem(text={Text("导出 PDF")},leadingIcon={AppIcon("export")},enabled=material.pages.isNotEmpty(),onClick={menu=false;onExport()})
                    DropdownMenuItem(text={Text(if(material.deleted) "恢复" else "移入回收站")},leadingIcon={AppIcon(if(material.deleted) "undo" else "trash")},onClick={menu=false;onTrash()})
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDialog(course: Course?,onDismiss: () -> Unit,onSave: (String,String) -> Unit) {
    var title by rememberSaveable(course?.id){mutableStateOf(course?.title.orEmpty())}
    var term by rememberSaveable(course?.id){mutableStateOf(course?.term.orEmpty())}
    val focus=remember{FocusRequester()}
    val manager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit){delay(250);focus.requestFocus();keyboard?.show()}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SheetHeading(if(course==null) "新建课程" else "编辑课程",onDismiss)
            OutlinedTextField(title,{title=it.take(120)},modifier=Modifier.fillMaxWidth().focusRequester(focus),label={Text("课程名称")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={manager.moveFocus(FocusDirection.Down)}),shape=RoundedCornerShape(14.dp))
            OutlinedTextField(term,{term=it.take(80)},modifier=Modifier.fillMaxWidth(),label={Text("学期 / 班级（选填）")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={if(title.isNotBlank()) onSave(title,term)}),shape=RoundedCornerShape(14.dp))
            Spacer(Modifier.height(4.dp))
            Button(onClick={onSave(title,term)},enabled=title.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){Text("保存")}
        }
    }
}
