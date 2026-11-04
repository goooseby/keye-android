package io.github.goooseby.keye.ui

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.UiState
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.domain.*
import io.github.goooseby.keye.network.SchoolConnection
import io.github.goooseby.keye.ui.components.*
import java.time.*
import kotlinx.coroutines.delay
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(state: UiState,model: KeyeViewModel,onLogin: ()->Unit,onOpen: (ScheduleEntry)->Unit,onFollow: (ScheduleEntry)->Unit) {
    var dateText by rememberSaveable {mutableStateOf(schoolToday().toString())}
    var overview by rememberSaveable {mutableStateOf(true)}
    var picking by remember {mutableStateOf(false)}
    var overlap by remember {mutableStateOf<List<ScheduleEntry>>(emptyList())}
    var detail by remember {mutableStateOf<ScheduleEntry?>(null)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true) {now=System.currentTimeMillis();delay(15_000)}}
    val date=LocalDate.parse(dateText)
    val monday=schoolWeek(date)
    val tt=state.timetable
    val entries=if(tt.week==monday.toString()) tt.entries else emptyList()
    val fresh=state.loggedIn && state.schoolConnection==SchoolConnection.Connected && !tt.fromCache && tt.error==null && scheduleIsFresh(tt.checkedAt,now)
    LaunchedEffect(dateText,state.loggedIn,state.hasTimetableCache) {model.loadTimetableWeek(dateText)}
    fun open(entry: ScheduleEntry) {
        val cached=state.materials.firstOrNull {it.sourceKey==entry.id && !it.deleted && it.pages.isNotEmpty()}
        if(fresh && entry.status==1) onFollow(entry)
        else if(entry.status==2 && cached==null) detail=entry
        else onOpen(entry)
    }
    LaunchedEffect(state.loggedIn) {if(!state.loggedIn) {detail=null;picking=false;overlap=emptyList()}}
    if(!state.loggedIn && !state.hasTimetableCache) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            EmptyState("calendar",if(state.schoolConnection==SchoolConnection.Expired) "登录已失效" else "登录后查看课表",
                detail="按日期找到课程，打开课件或进入课堂跟看",action=if(state.schoolConnection==SchoolConnection.Expired) "重新登录" else "学校登录",onAction=onLogin)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if(!state.loggedIn) Surface(color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(state.schoolConnection==SchoolConnection.Expired) "登录已失效 · 已保存课表仍可查看" else "未登录 · 显示已保存课表",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=onLogin) {Text("登录")}
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={dateText=date.minusWeeks(1).toString()}) {AppIcon("previous","上一周")}
            TextButton(onClick={picking=true},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)) {
                Text("${monday.monthValue}月${monday.dayOfMonth}日 – ${if(monday.monthValue!=monday.plusDays(6).monthValue) "${monday.plusDays(6).monthValue}月" else ""}${monday.plusDays(6).dayOfMonth}日",style=MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp));AppIcon("expand",modifier=Modifier.size(16.dp))
            }
            IconButton(onClick={dateText=date.plusWeeks(1).toString()}) {AppIcon("next","下一周")}
            TextButton(onClick={dateText=schoolToday().toString();model.loadTimetableWeek(dateText)}) {Text("今天")}
        }
        Row(Modifier.fillMaxWidth().padding(start=if(overview) 46.dp else 16.dp,end=if(overview) 12.dp else 16.dp,top=4.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(if(overview) 0.dp else 4.dp)) {
            val days=listOf("一","二","三","四","五","六","日")
            repeat(7) {i ->
                val day=monday.plusDays(i.toLong());val selected=day==date;val today=day==schoolToday()
                val hasLessons=entries.any {it.day==day.toString()}
                Surface(onClick={dateText=day.toString();overview=false},modifier=Modifier.weight(1f).semantics {contentDescription="${day.monthValue}月${day.dayOfMonth}日 星期${days[i]}${if(hasLessons) " 有课" else ""}";stateDescription=if(selected) "已选中" else "未选中"},
                    shape=RoundedCornerShape(18.dp),color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(if(today) "今" else days[i],style=MaterialTheme.typography.labelSmall,color=if(today||selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${day.dayOfMonth}",style=MaterialTheme.typography.titleMedium,fontWeight=if(selected||today) FontWeight.Bold else FontWeight.Medium)
                        Box(Modifier.size(4.dp).background(if(hasLessons) MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=0.dp,bottom=0.dp),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick={overview=!overview}) {AppIcon(if(overview) "tasks" else "grid",modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(overview) "当日课表" else "整周总览")}
            IconButton(onClick={if(state.loggedIn) model.loadTimetableWeek(dateText,force=true) else onLogin()},enabled=!tt.loading) {AppIcon("refresh","刷新课表",Modifier.size(20.dp))}
        }
        if(tt.error!=null) Text("暂时无法更新，已保存课件仍可阅读",Modifier.padding(horizontal=20.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        PullToRefreshBox(isRefreshing=tt.loading,onRefresh={if(state.loggedIn) model.loadTimetableWeek(dateText,force=true) else model.loadTimetableWeek(dateText)},modifier=Modifier.weight(1f)) {
            if(tt.loading && entries.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)}
            else if(entries.isEmpty() && tt.checkedAt==0L) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                EmptyState("calendar",if(!state.loggedIn) "这周尚未保存课表" else "暂时无法获取课表",detail=if(!state.loggedIn) "可切换已保存的周次，登录后获取这周课表" else "请检查网络后下拉重试")
            }
            else if(overview) WeekOverview(entries,monday,date,{dateText=it.toString();overview=false},fresh,::open,{overlap=it})
            else key(dateText) {
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    val daily=entries.filter {it.day==dateText}.sortedBy {it.startsAt ?: Long.MAX_VALUE}
                    if(daily.isEmpty()) item {EmptyState("calendar",if(!state.loggedIn && tt.checkedAt==0L) "这周尚未保存课表" else if(tt.error!=null && tt.checkedAt==0L) "暂时无法获取课表" else "这一天没有课程",detail=if(!state.loggedIn && tt.checkedAt==0L) "可切换已保存的周次，登录后获取这周课表" else if(tt.error!=null) "请检查网络后下拉重试" else "切换上方日期，或查看整周安排")}
                    items(daily,key=ScheduleEntry::id) {entry ->
                        val material=state.materials.firstOrNull {it.sourceKey==entry.id}
                        Row(Modifier.fillMaxWidth().animateItem(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.width(46.dp).padding(top=16.dp),horizontalAlignment=Alignment.Start) {
                                Text(lessonTime(entry.startsAt),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurface)
                                Text(entry.endsAt?.let {lessonTime(it)} ?: "",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.padding(start=3.dp,top=10.dp).width(2.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f),RoundedCornerShape(2.dp)))
                            }
                            LessonCard(entry,fresh,material?.let {if(it.deleted) "在回收站" else if(it.remotePageCount!=null && it.pages.size<it.remotePageCount) "已存 ${it.pages.size}/${it.remotePageCount} 页 · 待补全" else "已存 ${it.pages.size} 页"},Modifier.weight(1f)) {open(entry)}
                        }
                    }
                }
            }
        }
    }
    if(picking) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={picking=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let {dateText=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};picking=false}) {Text("前往")}},dismissButton={TextButton(onClick={picking=false}) {Text("取消")}}) {DatePicker(picker,title={Text("选择日期",Modifier.padding(start=24.dp,top=20.dp),style=MaterialTheme.typography.labelLarge)})}
    }
    if(overlap.isNotEmpty()) ModalBottomSheet(onDismissRequest={overlap=emptyList()}) {
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp)) {
            SheetHeading("选择课程",{overlap=emptyList()})
            overlap.forEach {old ->
                val entry=entries.firstOrNull {it.id==old.id} ?: old
                PreferenceRow("document",entry.title,"${lessonTime(entry.startsAt)}–${lessonTime(entry.endsAt)} · ${if(entry.status==1 && !fresh) "状态待更新" else lessonStatus(entry.status)}",onClick={overlap=emptyList();open(entry)})
            }
        }
    }
    detail?.let {entry ->ModalBottomSheet(onDismissRequest={detail=null}) {
        Column(Modifier.padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SheetHeading(entry.title,{detail=null})
            Text("${entry.day}  ${lessonTime(entry.startsAt)} – ${lessonTime(entry.endsAt)}",style=MaterialTheme.typography.bodyMedium)
            Text(listOf(entry.teacher,entry.room).filter {it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
            Text("课程尚未开始；老师如果已上传课件，可以先查看。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={detail=null;onOpen(entry)},modifier=Modifier.fillMaxWidth()) {Text("检查课件")}
        }
    }}
}

@Composable
private fun lessonTone(status: Int?,fresh: Boolean): Color = when {
    status==1 && fresh ->if(isSystemInDarkTheme()) Color(0xFF7CDEC3) else Color(0xFF147D68)
    status==3 ->if(isSystemInDarkTheme()) Color(0xFFEBC888) else Color(0xFF94671F)
    status==2 ->if(isSystemInDarkTheme()) Color(0xFFC9BAF4) else Color(0xFF7760A2)
    else ->MaterialTheme.colorScheme.primary
}

@Composable
private fun LessonCard(entry: ScheduleEntry,fresh: Boolean,saved: String?,modifier: Modifier,onClick: ()->Unit) {
    val tone=lessonTone(entry.status,fresh)
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(.7.dp,tone.copy(alpha=.15f))) {
        Row(Modifier.heightIn(min=96.dp).drawBehind {drawRect(tone,size=androidx.compose.ui.geometry.Size(3.dp.toPx(),size.height))}) {
            Column(Modifier.weight(1f).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(entry.title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    AppIcon("next",modifier=Modifier.size(16.dp))
                }
                val info=listOf(entry.teacher,entry.room).filter {it.isNotBlank()}.joinToString(" · ")
                if(info.isNotBlank()) Text(info,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(entry.status==1 && fresh) LiveBars(Modifier.size(16.dp),tone)
                    Text(if(entry.status==1 && !fresh) "状态待更新" else lessonStatus(entry.status),style=MaterialTheme.typography.labelSmall,color=tone)
                    saved?.let {Text("· $it",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                }
            }
        }
    }
}

@Composable
internal fun LiveBars(modifier: Modifier=Modifier,color: Color=MaterialTheme.colorScheme.primary) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val animate=ValueAnimator.areAnimatorsEnabled() && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val heights=if(animate) {
        val transition=rememberInfiniteTransition(label="live indicator")
        List(3) {index ->transition.animateFloat(.3f,1f,infiniteRepeatable(tween(450+index*130,delayMillis=index*110,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="live bar $index").value}
    } else listOf(.5f,1f,.7f)
    Canvas(modifier) {repeat(3) {i ->val x=size.width*(i+1)/4;drawLine(color,androidx.compose.ui.geometry.Offset(x,size.height),androidx.compose.ui.geometry.Offset(x,size.height*(1-heights[i])),strokeWidth=size.width/7,cap=StrokeCap.Round)}}
}

@Composable
internal fun LiveQuickEntry(state: UiState,onFollow: (ScheduleEntry)->Unit,onTimetable: ()->Unit) {
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true) {now=System.currentTimeMillis();delay(15_000)}}
    val live=state.timetable.entries.filter {it.status==1 && it.day==schoolToday().toString()}
    if(!state.loggedIn || state.schoolConnection!=SchoolConnection.Connected || state.timetable.fromCache || state.timetable.error!=null || !scheduleIsFresh(state.timetable.checkedAt,now) || live.isEmpty()) return
    val tone=if(isSystemInDarkTheme()) Color(0xFF7CDEC3) else Color(0xFF147D68)
    Surface(shape=RoundedCornerShape(20.dp),color=tone.copy(alpha=.08f)) {
        Column {
            live.take(2).forEach {entry ->
                Row(Modifier.fillMaxWidth().clickable {onFollow(entry)}.heightIn(min=66.dp).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    LiveBars(Modifier.size(22.dp),tone)
                    Column(Modifier.weight(1f)) {Text(entry.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);Text("直播中 · ${lessonTime(entry.startsAt)}–${lessonTime(entry.endsAt)}",style=MaterialTheme.typography.labelSmall,color=tone)}
                    AppIcon("next",modifier=Modifier.size(18.dp))
                }
            }
            if(live.size>2) TextButton(onClick=onTimetable,modifier=Modifier.fillMaxWidth()) {Text("查看 ${live.size} 节直播课程")}
        }
    }
}

@Composable
private fun WeekOverview(entries: List<ScheduleEntry>,monday: LocalDate,selected: LocalDate,onDate: (LocalDate)->Unit,fresh: Boolean,onOpen: (ScheduleEntry)->Unit,onOverlap: (List<ScheduleEntry>)->Unit) {
    val placements=placeLessons(entries)
    val start=(placements.minOfOrNull {it.startMinute/60} ?: 8).coerceAtMost(8).coerceAtLeast(0)
    val end=max(18,placements.maxOfOrNull {(it.endMinute+59)/60} ?: 18).coerceAtMost(24)
    val hour=60.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=8.dp,end=12.dp,bottom=24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(hour*(end-start))) {
            val dayWidth=(maxWidth-38.dp)/7
            Column {
                repeat(end-start) {index ->Row(Modifier.fillMaxWidth().height(hour)) {
                    Text("${(start+index).toString().padStart(2,'0')}:00",Modifier.width(38.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    repeat(7) {day ->Box(Modifier.width(dayWidth).fillMaxHeight().border(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
                        .background(if(monday.plusDays(day.toLong())==selected) MaterialTheme.colorScheme.primary.copy(alpha=.025f) else Color.Transparent)
                        .clickable {onDate(monday.plusDays(day.toLong()))})}
                }}
            }
            placements.groupBy {it.entry.day to it.group}.values.forEach {group ->
                val first=group.first()
                val day=runCatching {java.time.temporal.ChronoUnit.DAYS.between(monday,LocalDate.parse(first.entry.day)).toInt()}.getOrDefault(-1)
                if(day !in 0..6) return@forEach
                val top=hour*((group.minOf {it.startMinute}-start*60)/60f)
                val height=(hour*((group.maxOf {maxOf(it.endMinute,it.startMinute+48)}-group.minOf {it.startMinute})/60f)).coerceAtLeast(48.dp)
                val (background,accent)=courseTone(first.entry.groupId)
                val live=group.any {it.entry.status==1} && fresh
                val tone=if(live) lessonTone(1,true) else accent
                val label=if(group.size>1) "${group.size}节课重叠" else first.entry.title
                Surface(onClick={if(group.size>1) onOverlap(group.map {it.entry}) else onOpen(first.entry)},
                    modifier=Modifier.offset(x=38.dp+dayWidth*day,y=top).width(dayWidth).height(height).padding(1.dp)
                        .semantics {contentDescription=if(group.size>1) "${first.entry.day} ${group.size}节课程，点击选择" else "${first.entry.day} ${lessonTime(first.entry.startsAt)} ${first.entry.title} ${lessonStatus(first.entry.status)}"},
                    shape=RoundedCornerShape(9.dp),color=background,border=BorderStroke(.7.dp,tone.copy(alpha=.22f))) {
                    Column(Modifier.drawBehind {drawRect(tone,size=androidx.compose.ui.geometry.Size(2.dp.toPx(),size.height))}.padding(start=5.dp,end=3.dp,top=6.dp,bottom=5.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(label,fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurface,fontWeight=FontWeight.SemiBold,maxLines=if(height>100.dp) 5 else 3,overflow=TextOverflow.Ellipsis)
                        if(height>76.dp) Text(if(group.size>1) "点击选择" else if(first.entry.status==1 && !fresh) "待更新" else when(first.entry.status) {3 ->"生成中";5 ->"无回放";else ->lessonStatus(first.entry.status)},fontSize=9.sp,lineHeight=12.sp,color=if(first.entry.status==3) lessonTone(3,fresh) else tone,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val untimed=entries.filter {it.startsAt==null || it.endsAt==null || it.endsAt<=it.startsAt}
        if(untimed.isNotEmpty()) {
            Text("时间待确认",Modifier.padding(12.dp),style=MaterialTheme.typography.labelMedium)
            untimed.forEach {entry ->TextButton(onClick={onOpen(entry)},modifier=Modifier.fillMaxWidth()) {Text("${entry.day} · ${entry.title}")}}
        }
    }
}

@Composable
fun LessonPreparationScreen(entry: ScheduleEntry,state: UiState,model: KeyeViewModel,onBack: ()->Unit,onLogin: ()->Unit,onRetry: ()->Unit,error: String?=null) {
    androidx.activity.compose.BackHandler(onBack=onBack)
    val task=model.taskForLesson(entry.id)
    val running=error==null && (task==null || task.status in setOf("queued","running"))
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.height(56.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {IconButton(onClick=onBack) {AppIcon("back","返回课表")};Text(entry.title,style=MaterialTheme.typography.titleSmall)}
        Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            IconBadge("document",Modifier.size(60.dp));Spacer(Modifier.height(20.dp))
            Text(if(!state.loggedIn) "登录后获取课件" else if(running) "正在准备课件" else "课件暂时不可用",style=MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(if(!state.loggedIn) "本机已保存的内容仍可阅读" else error ?: task?.message ?: "正在查找课程页面",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            if(!state.loggedIn) Button(onClick=onLogin) {Text("学校登录")}
            else if(running) {LinearProgressIndicator(modifier=Modifier.width(180.dp));TextButton(onClick={task?.let {model.cancelTask(it.id)};onBack()}) {Text("取消获取")}}
            else Button(onClick=onRetry) {Text("重新检查")}
        }
    }
}
