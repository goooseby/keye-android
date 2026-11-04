package io.github.goooseby.keye.ui

import android.webkit.CookieManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.UiState
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.domain.ClassroomStage
import io.github.goooseby.keye.domain.isInProgress
import io.github.goooseby.keye.domain.semesterStart
import io.github.goooseby.keye.network.SchoolApi
import io.github.goooseby.keye.network.SchoolConnection
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import io.github.goooseby.keye.ui.components.PreferenceRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquireScreen(state: UiState,model: KeyeViewModel,onLogin: () -> Unit,onDownload: (List<ScheduleEntry>) -> Unit,onOpen: (String) -> Unit={},onFollow: (ScheduleEntry) -> Unit={},onResumeFollow: ()->Unit={}) {
    var pickingStart by remember{mutableStateOf<Boolean?>(null)}
    var start by remember(state.startDate){mutableStateOf(state.startDate)}
    var end by remember(state.endDate){mutableStateOf(state.endDate)}
    var selected by remember{mutableStateOf(emptySet<String>())}
    var expanded by remember(state.schedule) {mutableStateOf(emptySet<String>())}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true) {now=System.currentTimeMillis();kotlinx.coroutines.delay(15_000)}}
    val groups=remember(state.schedule) {state.schedule.groupBy(ScheduleEntry::groupId)}
    fun activeCapture(entry: ScheduleEntry)=state.classroom?.let {it.entry.id==entry.id && it.stage in setOf(ClassroomStage.Connecting,ClassroomStage.Connected,ClassroomStage.Waiting,ClassroomStage.Retrying)}==true
    fun selectable(entry: ScheduleEntry)=!activeCapture(entry) && state.materials.none {it.sourceKey==entry.id && it.deleted}
    LaunchedEffect(state.schedule,state.materials,state.classroom?.entry?.id,state.classroom?.stage) {
        val available=state.schedule.filter {selectable(it)}.map(ScheduleEntry::id).toSet()
        selected=selected.intersect(available)
    }
    AnimatedContent(state.loggedIn,transitionSpec={fadeIn(tween(220)) togetherWith fadeOut(tween(150))},label="account state") { connected ->
        if(!connected) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            EmptyState("school",if(state.schoolConnection==SchoolConnection.Expired) "学校登录已失效" else "尚未登录学校平台",detail="登录后查询课表与课件，本机资料仍可阅读",action=if(state.schoolConnection==SchoolConnection.Expired) "重新登录" else "登录学校平台",onAction=onLogin,modifier=Modifier.padding(bottom=48.dp))
        } else Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=16.dp)) {
                if(state.schoolConnection in setOf(SchoolConnection.Checking,SchoolConnection.Unverified)) item {
                    Surface(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(start=14.dp,end=6.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            if(state.schoolConnection==SchoolConnection.Checking) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else AppIcon("info",modifier=Modifier.size(20.dp))
                            Text(if(state.schoolConnection==SchoolConnection.Checking) "正在验证学校登录" else "暂时无法验证连接，登录信息已保留",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            if(state.schoolConnection==SchoolConnection.Unverified) TextButton(onClick={model.checkSchoolSession(CookieManager.getInstance().getCookie(SchoolApi.HOME),force=true)}) {Text("重试")}
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            DateCell("开始日期",start,{pickingStart=true},Modifier.weight(1f))
                            Text("—",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            DateCell("结束日期",end,{pickingStart=false},Modifier.weight(1f))
                            IconButton(onClick={model.scan(start,end)},enabled=!state.busy) {
                                if(state.busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else AppIcon("search","查询课表",Modifier.size(22.dp))
                            }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(onClick={end=LocalDate.now().toString();start=end;model.scan(start,end)},enabled=!state.busy,label={Text("今天")})
                            SuggestionChip(onClick={end=LocalDate.now().toString();start=LocalDate.now().minusDays(14).toString();model.scan(start,end)},enabled=!state.busy,label={Text("近两周")})
                            SuggestionChip(onClick={end=LocalDate.now().toString();start=semesterStart(LocalDate.now()).toString();model.scan(start,end)},enabled=!state.busy,label={Text("本学期")})
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            Text("${groups.size} 门 · ${state.schedule.size} 节",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    state.classroom?.let {session ->
                        PreferenceRow("live","继续课堂跟看",session.entry.title+" · "+when(session.stage) {ClassroomStage.Ended ->"已结束";ClassroomStage.NeedsLogin ->"需登录";else ->"已保存，可继续"},onClick=onResumeFollow)
                    } ?: Text("选择课次下载或检查新增；已保存的课件可直接打开",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(state.schedule.isEmpty()) item {EmptyState(if(state.busy) "clock" else "calendar",if(state.busy) "正在查询课表" else "暂无课次",detail=if(state.busy) null else "选择日期范围后查询")}
                if(groups.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("课程",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val allExpanded=expanded.containsAll(groups.keys)
                        TextButton(onClick={expanded=if(allExpanded) emptySet() else groups.keys.toSet()}) {
                            AppIcon("expand",modifier=Modifier.size(18.dp).graphicsLayer {rotationZ=if(allExpanded) 180f else 0f})
                            Spacer(Modifier.width(6.dp));Text(if(allExpanded) "全部折叠" else "全部展开")
                        }
                    }
                }
                groups.forEach{(group,entries) ->
                    item(key="header:$group") {
                        val available=entries.filter {selectable(it)}.map(ScheduleEntry::id).toSet()
                        val open=group in expanded
                        val chosen=entries.count {it.id in selected}
                        val rotation by animateFloatAsState(if(open) 90f else 0f,tween(180),label="course expansion")
                        Row(Modifier.fillMaxWidth().animateItem().clickable {expanded=if(open) expanded-group else expanded+group}
                            .semantics {stateDescription=if(open) "已展开" else "已折叠"}
                            .heightIn(min=64.dp).padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            AppIcon("next",modifier=Modifier.size(18.dp).graphicsLayer {rotationZ=rotation})
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(entries.first().title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${entries.size} 节"+if(chosen>0) " · 已选 $chosen 节" else "",style=MaterialTheme.typography.bodySmall,color=if(chosen>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TriStateCheckbox(state=when {available.isNotEmpty()&&selected.containsAll(available) ->ToggleableState.On;available.any {it in selected} ->ToggleableState.Indeterminate;else ->ToggleableState.Off},
                                onClick={selected=if(selected.containsAll(available)) selected-available else selected+available},enabled=available.isNotEmpty(),modifier=Modifier.semantics{contentDescription="全选${entries.first().title}"})
                        }
                    }
                    if(group in expanded) items(entries,key=ScheduleEntry::id){entry ->
                        val cached=state.materials.firstOrNull {it.sourceKey==entry.id}
                        val downloaded=cached?.let {it.pages.isNotEmpty() || it.deleted}==true
                        val checked=entry.id in selected
                        val tone by animateColorAsState(if(checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,label="lecture selection")
                        Surface(onClick={if(downloaded) state.materials.firstOrNull{it.sourceKey==entry.id}?.let{if(!it.deleted) onOpen(it.id) else model.notice("课件在回收站中，可在我的中恢复")} else if(activeCapture(entry)) onFollow(entry) else selected=if(checked) selected-entry.id else selected+entry.id},color=tone,modifier=Modifier.animateItem()) {
                            Column {
                                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(start=24.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    AppIcon("document",modifier=Modifier.size(19.dp))
                                    Column(Modifier.weight(1f).padding(vertical=6.dp)) {
                                        Text(runCatching{LocalDate.parse(entry.day).format(DateTimeFormatter.ofPattern("M月d日  EEE",Locale.SIMPLIFIED_CHINESE))}.getOrDefault(entry.day),style=MaterialTheme.typography.bodyMedium)
                                        if(cached!=null && !cached.deleted) Text(if(cached.remotePageCount!=null && cached.pages.size<cached.remotePageCount) "已保存 ${cached.pages.size} / ${cached.remotePageCount} 页 · 待补全" else "已保存 ${cached.pages.size} 页",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if((entry.status==1 && io.github.goooseby.keye.domain.scheduleIsFresh(state.scheduleCheckedAt,now)) || activeCapture(entry)) TextButton(onClick={onFollow(entry)},contentPadding=PaddingValues(horizontal=8.dp)) {AppIcon("live",modifier=Modifier.size(17.dp));Spacer(Modifier.width(4.dp));Text(if(entry.status==1 && io.github.goooseby.keye.domain.scheduleIsFresh(state.scheduleCheckedAt,now)) "直播中 · 跟看" else "继续跟看",style=MaterialTheme.typography.labelMedium)}
                                    if(downloaded) IconButton(onClick={if(cached?.deleted==true) model.notice("课件在回收站中，请先恢复") else cached?.let {onOpen(it.id)}}) {AppIcon("document","打开已保存课件",Modifier.size(20.dp))}
                                    if(!activeCapture(entry)) Checkbox(checked,{selected=if(it) selected+entry.id else selected-entry.id},enabled=selectable(entry),modifier=Modifier.semantics {contentDescription=if(cached!=null) "检查并补全课件" else "选择下载课件"})
                                }
                                HorizontalDivider(Modifier.padding(start=55.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(selected.isNotEmpty(),enter=slideInVertically{it}+fadeIn(),exit=slideOutVertically{it}+fadeOut()) {
                Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=6.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 节",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        Button(onClick={onDownload(state.schedule.filter{it.id in selected});selected=emptySet()},shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=22.dp,vertical=13.dp)) {AppIcon("download",modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(state.schedule.any {it.id in selected && state.materials.any {m ->m.sourceKey==it.id}}) "检查并补全" else "下载课件")}
                    }
                }
            }
        }
    }
    if(pickingStart!=null) {
        val first=pickingStart==true
        val initial=runCatching{LocalDate.parse(if(first) start else end)}.getOrDefault(LocalDate.now())
        val picker=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={pickingStart=null},confirmButton={TextButton(onClick={
            picker.selectedDateMillis?.let{value ->val date=Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString();if(first) start=date else end=date}
            pickingStart=null
        },enabled=picker.selectedDateMillis!=null){Text("确定")}},dismissButton={TextButton(onClick={pickingStart=null}){Text("取消")}}) {
            DatePicker(picker,title={Text(if(first) "选择开始日期" else "选择结束日期",Modifier.padding(start=24.dp,top=20.dp),style=MaterialTheme.typography.labelLarge)})
        }
    }

}
@Composable
private fun DateCell(label: String,value: String,onClick: () -> Unit,modifier: Modifier) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(13.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.labelLarge,maxLines=1)
        }
    }
}
