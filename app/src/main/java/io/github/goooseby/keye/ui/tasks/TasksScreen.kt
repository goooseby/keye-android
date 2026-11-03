package io.github.goooseby.keye.ui.tasks

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.app.KeyeViewModel
import io.github.goooseby.keye.data.model.TaskRecord
import io.github.goooseby.keye.data.model.taskQueueOrder
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import io.github.goooseby.keye.ui.components.IconBadge
import io.github.goooseby.keye.ui.components.PillTabs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(tasks: List<TaskRecord>,model: KeyeViewModel,onOpen: (String) -> Unit,onOutput: (String) -> Unit) {
    var filter by rememberSaveable{mutableIntStateOf(0)}
    val visible=tasks.filter{when(filter){1 ->it.status in setOf("queued","running");2 ->it.status in setOf("failed","cancelled");else ->true}}.sortedWith(taskQueueOrder)
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("全部","进行中","未完成"),filter,{filter=it},Modifier.padding(horizontal=20.dp,vertical=4.dp))
        key(filter) {AnimatedTaskQueue(visible,tasks,model,onOpen,onOutput,when(filter){1 ->"没有进行中的任务";2 ->"没有未完成任务";else ->"暂无任务"})}
    }
}

@Composable
private fun AnimatedTaskQueue(tasks: List<TaskRecord>,allTasks: List<TaskRecord>,model: KeyeViewModel,onOpen: (String) -> Unit,onOutput: (String) -> Unit,emptyLabel: String) {
    var displayed by remember {mutableStateOf(tasks)}
    val desired=tasks.map {it.id}.toSet()
    val records=allTasks.associateBy {it.id}
    val initialIds=remember {tasks.map {it.id}.toSet()}
    val list=rememberLazyListState()
    LaunchedEffect(desired) {
        val atTop=list.firstVisibleItemIndex==0 && list.firstVisibleItemScrollOffset==0
        val existing=displayed.map {it.id}.toSet()
        val incoming=tasks.filter {it.id !in existing}
        displayed=incoming+displayed
        if(atTop && incoming.isNotEmpty()) list.requestScrollToItem(0)
    }
    LazyColumn(state=list,contentPadding=PaddingValues(top=12.dp,bottom=24.dp)) {
        if(displayed.isEmpty()) item {EmptyState("tasks",emptyLabel)}
        items(displayed,key=TaskRecord::id) {snapshot ->
            val record=records[snapshot.id] ?: snapshot
            val transition=remember {MutableTransitionState(snapshot.id in initialIds).apply {targetState=true}}
            LaunchedEffect(snapshot.id in desired) {transition.targetState=snapshot.id in desired}
            val latestDesired by rememberUpdatedState(desired)
            LaunchedEffect(transition.isIdle,transition.currentState) {
                if(transition.isIdle && !transition.currentState && snapshot.id !in latestDesired) displayed=displayed.filterNot {it.id==snapshot.id}
            }
            // Height and horizontal translation have a single owner. Combining this
            // with lazy animateItem placement would repeatedly retarget neighbours.
            AnimatedVisibility(transition,enter=slideInHorizontally(tween(240,easing=FastOutSlowInEasing)){it/6}+fadeIn(tween(180))+expandVertically(tween(240,easing=FastOutSlowInEasing),expandFrom=Alignment.Top),
                exit=slideOutHorizontally(tween(220,easing=FastOutSlowInEasing)){it/4}+fadeOut(tween(170))+shrinkVertically(tween(260,delayMillis=40,easing=FastOutSlowInEasing),shrinkTowards=Alignment.Top)) {
                TaskRow(record,{model.cancelTask(record.id)},{model.retryTask(record.id)},onOpen,onOutput)
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskRecord,onCancel: () -> Unit,onRetry: () -> Unit,onOpen: (String) -> Unit,onOutput: (String) -> Unit,modifier: Modifier=Modifier) {
    val active=task.status in setOf("queued","running")
    val retryable=task.type in setOf("download","import")
    val failed=task.status=="failed"
    val color=when{failed ->MaterialTheme.colorScheme.error;active || task.status=="done" ->MaterialTheme.colorScheme.primary;else ->MaterialTheme.colorScheme.onSurfaceVariant}
    val status=when(task.status){"queued" ->"等待中";"running" ->"下载中".takeIf{task.type=="download"}?:"处理中";"done" ->"已完成";"cancelled" ->"已取消";else ->"失败"}
    val type=when(task.type){"download" ->"下载";"import" ->"导入";else ->"导出"}
    val date=remember(task.updatedAt){SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(task.updatedAt))}
    val progress by animateFloatAsState((task.progress/100f).coerceIn(0f,1f),tween(350),label="task progress")
    var details by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)}
    val output=task.type=="export"&&task.status=="done"&&task.payload.startsWith("content://")
    Surface(onClick={details=true},modifier=modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                IconBadge(when(task.type){"download" ->"download";"import" ->"document";else ->"export"},Modifier.size(34.dp),tint=color,background=color.copy(alpha=.08f))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(task.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(active) "$status · ${task.progress}%" else status,color=color,style=MaterialTheme.typography.bodySmall,maxLines=1)
                        Text(if(active) task.message else "$type · $date",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        if(active) LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(3.dp),color=color,trackColor=color.copy(alpha=.1f),strokeCap=androidx.compose.ui.graphics.StrokeCap.Round,gapSize=0.dp,drawStopIndicator={})
                    }
                }
                Box {
                    when {
                        active&&retryable -> IconButton(onClick=onCancel){AppIcon("close","取消任务",Modifier.size(20.dp))}
                        retryable&&task.status in setOf("failed","cancelled") -> IconButton(onClick=onRetry){AppIcon("refresh","重试任务",Modifier.size(21.dp))}
                        task.status=="done"&&task.materialId!=null&&!output -> IconButton(onClick={onOpen(task.materialId)}){AppIcon("next","打开课件",Modifier.size(20.dp))}
                        output -> {
                            IconButton(onClick={menu=true}){AppIcon("more","任务操作",Modifier.size(20.dp))}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text("查看 PDF")},onClick={menu=false;onOutput(task.payload)})
                                task.materialId?.let{id ->DropdownMenuItem(text={Text("打开课件")},onClick={menu=false;onOpen(id)})}
                            }
                        }
                        else -> IconButton(onClick={details=true}){AppIcon("info","任务详情",Modifier.size(20.dp))}
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start=66.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
        }
    }
    if(details) AlertDialog(onDismissRequest={details=false},title={Text(task.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("$type · $status · $date",style=MaterialTheme.typography.bodySmall,color=color)
            if(task.message.isNotBlank()) Text(task.message,style=MaterialTheme.typography.bodyMedium)
        }
    },confirmButton={TextButton(onClick={details=false}){Text("关闭")}})
}
