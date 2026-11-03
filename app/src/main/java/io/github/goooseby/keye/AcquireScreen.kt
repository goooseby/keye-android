package io.github.goooseby.keye

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquireScreen(state: UiState,model: KeyeViewModel,onLogin: () -> Unit,onDownload: (List<ScheduleEntry>) -> Unit,onOpen: (String) -> Unit={}) {
    var pickingStart by remember{mutableStateOf<Boolean?>(null)}
    var start by remember(state.startDate){mutableStateOf(state.startDate)}
    var end by remember(state.endDate){mutableStateOf(state.endDate)}
    var selected by remember{mutableStateOf(emptySet<String>())}
    LaunchedEffect(state.schedule,state.materials) {
        val available=state.schedule.filter{entry ->state.materials.none{it.sourceKey==entry.id}}.map(ScheduleEntry::id).toSet()
        selected=selected.intersect(available)
    }
    AnimatedContent(state.loggedIn,transitionSpec={fadeIn(tween(220)) togetherWith fadeOut(tween(150))},label="account state") { connected ->
        if(!connected) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            EmptyState("school","尚未连接学校平台",detail="登录后查询课表与课件",action="登录学校平台",onAction=onLogin,modifier=Modifier.padding(bottom=48.dp))
        } else Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=16.dp)) {
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
                            SuggestionChip(onClick={end=LocalDate.now().toString();start=LocalDate.now().minusDays(14).toString();model.scan(start,end)},enabled=!state.busy,label={Text("近两周")})
                            SuggestionChip(onClick={end=LocalDate.now().toString();start=LocalDate.now().minusDays(30).toString();model.scan(start,end)},enabled=!state.busy,label={Text("近一月")})
                            Spacer(Modifier.weight(1f))
                            Text("${state.schedule.size} 节课",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(state.schedule.isEmpty()) item {EmptyState(if(state.busy) "clock" else "calendar",if(state.busy) "正在查询课表" else "暂无课次",detail=if(state.busy) null else "选择日期范围后查询")}
                state.schedule.groupBy(ScheduleEntry::groupId).forEach{(group,entries) ->
                    item(key="header:$group") {
                        val available=entries.filter{entry ->state.materials.none{it.sourceKey==entry.id}}.map(ScheduleEntry::id).toSet()
                        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(entries.first().title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${entries.size} 节",Modifier.padding(horizontal=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Checkbox(checked=available.isNotEmpty()&&selected.containsAll(available),onCheckedChange={selected=if(it) selected+available else selected-available},enabled=available.isNotEmpty(),modifier=Modifier.semantics{contentDescription="全选${entries.first().title}"})
                        }
                    }
                    items(entries,key=ScheduleEntry::id){entry ->
                        val downloaded=state.materials.any{it.sourceKey==entry.id}
                        val checked=entry.id in selected
                        val tone by animateColorAsState(if(checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,label="lecture selection")
                        Surface(onClick={if(downloaded) state.materials.firstOrNull{it.sourceKey==entry.id}?.let{if(!it.deleted) onOpen(it.id) else model.notice("课件在回收站中，可在我的中恢复")} else selected=if(checked) selected-entry.id else selected+entry.id},color=tone,modifier=Modifier.animateItem()) {
                            Column {
                                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(start=24.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    AppIcon("document",modifier=Modifier.size(19.dp))
                                    Text(runCatching{LocalDate.parse(entry.day).format(DateTimeFormatter.ofPattern("M月d日  EEE",Locale.SIMPLIFIED_CHINESE))}.getOrDefault(entry.day),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                    if(downloaded) Text("打开",Modifier.padding(end=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    else Checkbox(checked,{selected=if(it) selected+entry.id else selected-entry.id})
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
                        Button(onClick={onDownload(state.schedule.filter{it.id in selected});selected=emptySet()},shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=22.dp,vertical=13.dp)) {AppIcon("download",modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("下载课件")}
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
