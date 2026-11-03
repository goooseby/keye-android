package io.github.goooseby.keye.ui.settings

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.app.KeyeViewModel
import io.github.goooseby.keye.app.UiState
import io.github.goooseby.keye.data.school.SchoolApi
import io.github.goooseby.keye.data.school.SchoolConnection
import io.github.goooseby.keye.data.school.schoolConnectionLabel
import io.github.goooseby.keye.export.ExportLocationRow
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.IconBadge
import io.github.goooseby.keye.ui.components.PreferenceRow
import io.github.goooseby.keye.ui.components.SheetHeading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState,model: KeyeViewModel,onLogin: () -> Unit,onTasks: () -> Unit={},onTrash: () -> Unit={},onUpdates: () -> Unit={},updateStatus: String?=null) {
    val context=LocalContext.current
    val currentVersion=remember {context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()}
    var networkOpen by remember{mutableStateOf(false)}
    var confirmLogout by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp,4.dp,20.dp,12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconBadge("school",Modifier.size(42.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("学校平台",style=MaterialTheme.typography.titleSmall)
                        Text(schoolConnectionLabel(state.schoolConnection),style=MaterialTheme.typography.labelMedium,
                            color=if(state.schoolConnection==SchoolConnection.Expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    if(!state.loggedIn) FilledTonalButton(onClick=onLogin){Text(if(state.schoolConnection==SchoolConnection.Expired) "重新登录" else "登录")}
                    else IconButton(onClick={confirmLogout=true}) {AppIcon("logout","退出登录")}
                    if(state.schoolConnection==SchoolConnection.Unverified) IconButton(onClick={model.checkSchoolSession(CookieManager.getInstance().getCookie(SchoolApi.HOME),force=true)}) {AppIcon("refresh","重新检查连接")}
                }
            }
        }
        item {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    PreferenceRow("tasks","下载管理",compact=true,onClick=onTasks)
                    HorizontalDivider(Modifier.padding(start=62.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    PreferenceRow("download","下载参数","超时 ${state.settings.timeoutSeconds} 秒 · 重试 ${state.settings.retryCount} 次",compact=true,onClick={networkOpen=true})
                }
            }
        }
        item {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    ExportLocationRow(compact=true)
                    HorizontalDivider(Modifier.padding(start=62.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    PreferenceRow("trash","回收站",compact=true,onClick=onTrash)
                }
            }
        }
        item {Text("课件与阅读进度保存在本机，卸载前请先导出。",Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    PreferenceRow("refresh","应用更新",subtitle=updateStatus?:"当前版本 $currentVersion",compact=true,onClick=onUpdates)
                    HorizontalDivider(Modifier.padding(start=62.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    PreferenceRow("code","GitHub · Keye","代码公开，欢迎探索和改进",compact=true,onClick={
                        runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/goooseby/keye-android")))}
                            .onFailure {model.notice("暂时无法打开浏览器")}
                    })
                }
            }
        }
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
