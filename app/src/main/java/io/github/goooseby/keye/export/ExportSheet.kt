package io.github.goooseby.keye.export

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.domain.selectExportPages
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.PreferenceRow
import io.github.goooseby.keye.ui.components.SheetHeading

@Composable
fun ExportLocationRow(compact: Boolean=false) {
    val context=LocalContext.current
    val storage=remember { ExportStorage(context) }
    var label by remember { mutableStateOf(storage.location) }
    var problem by remember {mutableStateOf<String?>(null)}
    val chooser=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) runCatching { storage.setFolder(uri);label=storage.location;problem=null }.onFailure {problem="无法使用这个文件夹，请重新选择"}
    }
    Column {
        PreferenceRow("folder","导出位置",label,compact=compact,onClick={chooser.launch(storage.folder)})
        problem?.let {Text(it,Modifier.padding(horizontal=20.dp,vertical=8.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(materials: List<StudyMaterial>,shareOnly: Boolean=false,onDismiss: () -> Unit,onSubmit: (ExportRequest) -> Unit) {
    val context=LocalContext.current
    val storage=remember { ExportStorage(context) }
    val single=materials.singleOrNull()
    val count=materials.sumOf { it.pages.size }
    val keptCount=materials.sumOf {it.keptCount}
    var name by rememberSaveable { mutableStateOf(single?.let { if(it.title.contains(it.day)) it.title else "${it.title}_${it.day}" } ?: "课程课件_${java.time.LocalDate.now()}") }
    var includeNotes by rememberSaveable {mutableStateOf(true)}
    var range by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var location by remember { mutableStateOf(storage.location) }
    var locationError by remember { mutableStateOf<String?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) runCatching { storage.setFolder(uri);location=storage.location;locationError=null }.onFailure { locationError="无法使用这个文件夹，请重新选择" }
    }
    val parsed=remember(text,count) { runCatching { parsePageRange(text,count) } }
    val flat=when(range) {1 ->setOf(single?.lastPage ?: 1);2 ->parsed.getOrNull() ?: emptySet();else ->(1..count).toSet()}
    val selected=selectExportPages(materials,flat)
    val selectedCount=selected.values.sumOf {it.size}
    val valid=name.isNotBlank() && selectedCount>0 && (range!=2 || parsed.isSuccess)
    fun submit(action: ExportAction) {
        if(action!=ExportAction.Share && Build.VERSION.SDK_INT<29 && storage.folder==null) {picker.launch(null);return}
        onSubmit(ExportRequest(materials.map {it.id},name,selected,action,includeNotes=includeNotes,snapshots=materials))
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SheetHeading(if(shareOnly) "分享 PDF" else "导出 PDF",onDismiss)
            OutlinedTextField(name,{name=it.take(100)},label={Text("文件名")},suffix={Text(".pdf")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
            Text("保留 $keptCount 页"+(if(keptCount<count) " · 原 $count 页" else "")+if(materials.size>1) " · ${materials.size} 份课件按当前顺序合并" else "",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(range==0,{range=0},label={Text("全部")})
                if(single!=null) FilterChip(range==1,{range=1},label={Text("当前页")})
                FilterChip(range==2,{range=2},label={Text("指定页")})
            }
            if(range==2) OutlinedTextField(text,{text=it},modifier=Modifier.fillMaxWidth(),label={Text("页码范围")},placeholder={Text("1,3-5,8")},supportingText={Text(if(text.isBlank()) "按原始页码选择，自动跳过已移除页面" else parsed.exceptionOrNull()?.message ?: if(selectedCount==0) "所选范围中没有可导出页面" else "已选 $selectedCount 页")},isError=text.isNotBlank()&&(parsed.isFailure||selectedCount==0),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),shape=RoundedCornerShape(14.dp))
            if(materials.any {it.pageNotes.isNotEmpty()}) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("包含页批注",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(includeNotes,{includeNotes=it})
            }
            if(!shareOnly) {
                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(start=14.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        AppIcon("folder",modifier=Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(vertical=10.dp)) {Text("保存到",style=MaterialTheme.typography.labelSmall);Text(location,style=MaterialTheme.typography.bodyMedium)}
                        TextButton(onClick={picker.launch(storage.folder)}) { Text("更改") }
                    }
                }
                locationError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            }
            Text(if(single?.kind=="pdf"&&range==0&&single.excluded.isEmpty()&&(!includeNotes||single.pageNotes.isEmpty())) "保留原始 PDF 的文字与清晰度" else "生成图片型 PDF，所选页面不影响原课件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(!shareOnly) OutlinedButton(onClick={submit(ExportAction.Save)},enabled=valid,modifier=Modifier.weight(1f).heightIn(min=50.dp),shape=RoundedCornerShape(14.dp)) {Text("导出")}
                Button(onClick={submit(if(shareOnly) ExportAction.Share else ExportAction.SaveAndShare)},enabled=valid,modifier=Modifier.weight(1f).heightIn(min=50.dp),shape=RoundedCornerShape(14.dp)) {Text(if(shareOnly) "分享 PDF" else "导出并分享")}
            }
        }
    }
}

@Composable
fun ExportFeedback(state: ExportState?,model: KeyeViewModel,origin: String="") {
    if(state==null) return
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val storage=remember { ExportStorage(context) }
    val result=state.result
    fun share() { if(result!=null) runCatching { storage.share(result) }.onFailure { model.notice("无法打开分享，请重试") } }
    LaunchedEffect(result) {
        if(result!=null && state.autoShare) {
            model.consumeShare()
            if(state.origin==origin && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) share()
        }
    }
    if(state.running) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shadowElevation=5.dp,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(start=16.dp,end=6.dp,top=6.dp,bottom=12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(state.message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={model.cancelExport()}) {Text("取消")}
            }
            LinearProgressIndicator(progress={state.progress},modifier=Modifier.fillMaxWidth().height(3.dp))
        }
    } else Surface(color=MaterialTheme.colorScheme.surfaceContainer,shadowElevation=5.dp,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(start=14.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical=10.dp)) {
                Text(state.message,style=MaterialTheme.typography.bodySmall,color=if(state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                result?.let {Text(it.name,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
            }
            if(state.failed&&state.retryable) TextButton(onClick={model.retryExport()}) {Text("重试")}
            if(result!=null) {
                IconButton(onClick={runCatching { storage.open(result) }.onFailure { model.notice("未找到可用的 PDF 阅读器") }}) {AppIcon("document","打开导出文件",Modifier.size(20.dp))}
                IconButton(onClick={share()}) {AppIcon("share","分享导出文件",Modifier.size(20.dp))}
            }
            IconButton(onClick={model.clearExport()}) {AppIcon("close","关闭导出提示",Modifier.size(18.dp))}
        }
    }
}
