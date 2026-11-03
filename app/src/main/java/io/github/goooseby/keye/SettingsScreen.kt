package io.github.goooseby.keye

import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState,model: KeyeViewModel,onLogin: () -> Unit,onTasks: () -> Unit={},onTrash: () -> Unit={}) {
    var networkOpen by remember{mutableStateOf(false)}
    var confirmLogout by remember{mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(20.dp,12.dp,20.dp,28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape=RoundedCornerShape(23.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    IconBadge("school",Modifier.size(54.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("学校平台",style=MaterialTheme.typography.titleMedium)
                        StatusPill(if(state.loggedIn) "已连接" else "未登录",if(state.loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(!state.loggedIn) FilledTonalButton(onClick=onLogin,shape=RoundedCornerShape(12.dp)){Text("登录")}
                    else IconButton(onClick={confirmLogout=true}){AppIcon("logout","退出登录")}
                }
            }
        }
        item{Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer){PreferenceRow("tasks","下载管理",onClick=onTasks)}}
        item{SectionLabel("下载")}
        item{
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                PreferenceRow("download","下载参数","超时 ${state.settings.timeoutSeconds} 秒 · 重试 ${state.settings.retryCount} 次",onClick={networkOpen=true})
            }
        }
        item{SectionLabel("资料")}
        item{
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    PreferenceRow("storage","本机资料","课程、原件与阅读进度保存在本机")
                    HorizontalDivider(Modifier.padding(start=70.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    ExportLocationRow()
                    HorizontalDivider(Modifier.padding(start=70.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    PreferenceRow("trash","回收站",onClick=onTrash)
                }
            }
        }
        item{Text("卸载应用前，请先导出需要保留的课件。",Modifier.padding(horizontal=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{SectionLabel("关于")}
        item{Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer){PreferenceRow("info","课页",value="0.1.0")}}
    }
    if(confirmLogout) AlertDialog(onDismissRequest={confirmLogout=false},icon={AppIcon("logout")},title={Text("退出学校登录？")},text={Text("本机课件与阅读进度会保留。")},confirmButton={TextButton(onClick={CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();model.logout();confirmLogout=false}){Text("退出")}},dismissButton={TextButton(onClick={confirmLogout=false}){Text("取消")}})
    if(networkOpen) {
        var timeout by remember{mutableStateOf(state.settings.timeoutSeconds.toString())}
        var retries by remember{mutableStateOf(state.settings.retryCount.toString())}
        var gap by remember{mutableStateOf(state.settings.requestGapMillis.toString())}
        val t=timeout.toIntOrNull();val r=retries.toIntOrNull();val g=gap.toLongOrNull()
        val valid=t!=null&&t in 5..600&&r!=null&&r in 1..10&&g!=null&&g in 0L..5000L
        ModalBottomSheet(onDismissRequest={networkOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SheetHeading("下载参数",{networkOpen=false})
                OutlinedTextField(timeout,{timeout=it.filter(Char::isDigit).take(3)},modifier=Modifier.fillMaxWidth(),label={Text("请求超时")},suffix={Text("秒")},supportingText={Text("5–600 秒")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),shape=RoundedCornerShape(14.dp),isError=t!=null&&t !in 5..600)
                OutlinedTextField(retries,{retries=it.filter(Char::isDigit).take(2)},modifier=Modifier.fillMaxWidth(),label={Text("单页重试次数")},supportingText={Text("1–10 次")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),shape=RoundedCornerShape(14.dp),isError=r!=null&&r !in 1..10)
                OutlinedTextField(gap,{gap=it.filter(Char::isDigit).take(4)},modifier=Modifier.fillMaxWidth(),label={Text("页面请求间隔")},suffix={Text("毫秒")},supportingText={Text("0–5000 毫秒")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),shape=RoundedCornerShape(14.dp),isError=g!=null&&g !in 0L..5000L)
                Button(onClick={if(valid){model.settings(state.settings.copy(timeoutSeconds=t!!,retryCount=r!!,requestGapMillis=g!!));networkOpen=false}},enabled=valid,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){Text("保存设置")}
            }
        }
    }
}
