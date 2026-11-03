package io.github.goooseby.keye.update

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.IconBadge
import io.github.goooseby.keye.ui.components.SheetHeading
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppUpdateHost(model: AppUpdateViewModel,canPrompt: Boolean) {
    val state by model.state.collectAsState()
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val resumed=lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val updatePrefs=remember {context.getSharedPreferences("app_updates",android.content.Context.MODE_PRIVATE)}
    var permissionDialog by rememberSaveable {mutableStateOf(false)}
    // Opening details is an explicit action (including a completion notification),
    // so it may continue over a reader. Only automatic prompts must wait.
    val allowInteraction=canPrompt || state.panelOpen || permissionDialog
    val installPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) model.requestInstall()
        else model.installError("尚未允许课页安装更新；安装包已保留，可以稍后继续")
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {model.startDownload()}
    fun download() {
        if(Build.VERSION.SDK_INT>=33 && !updatePrefs.getBoolean("notification_permission_asked",false) && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            updatePrefs.edit().putBoolean("notification_permission_asked",true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.startDownload()
    }
    LaunchedEffect(owner,state.download.stage) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refreshDownload()
            if(state.download.stage==UpdateDownloadStage.Downloading) {
                while(true) {delay(1500);model.refreshDownload()}
            }
        }
    }
    LaunchedEffect(state.download.stage,state.autoInstallPending,allowInteraction,resumed) {
        if(resumed && allowInteraction && state.autoInstallPending && state.download.stage==UpdateDownloadStage.Ready) model.requestInstall()
    }
    LaunchedEffect(state.installer,allowInteraction,resumed) {
        val intent=state.installer
        if(intent!=null && resumed && allowInteraction) {
            model.consumeInstaller()
            if(!context.packageManager.canRequestPackageInstalls()) permissionDialog=true
            else runCatching {context.startActivity(intent)}.onFailure {model.installError("系统无法打开安装程序；安装包已保留，请稍后重试")}
        }
    }
    if(canPrompt && resumed && state.prompt && !state.panelOpen) {
        LaunchedEffect(state.release?.version) {model.markPromptShown()}
        AlertDialog(onDismissRequest=model::dismiss,icon={AppIcon("download")},title={Text("发现课页 ${state.release?.version}")},text={
            Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                state.release?.let {release ->
                    Text(release.notes.ifBlank {"此版本提供功能改进与问题修复。"},style=MaterialTheme.typography.bodyMedium)
                    Text("安装包 ${updateSize(release.size)} · 可能使用移动数据，下载在后台继续",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={model.setAutomatic(false);model.dismiss()}) {Text("关闭自动提醒") }
                }
            }
        },confirmButton={TextButton(onClick={download()}) {Text("下载更新")}},dismissButton={
            Row {
                TextButton(onClick=model::dismiss) {Text("稍后")}
                TextButton(onClick=model::ignoreVersion) {Text("忽略此版本")}
            }
        })
    }
    if(allowInteraction && resumed && state.panelOpen) ModalBottomSheet(onDismissRequest=model::dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SheetHeading("应用更新",model::dismiss)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                IconBadge("refresh")
                Column(Modifier.weight(1f)) {
                    Text("课页 ${state.version}",style=MaterialTheme.typography.titleMedium)
                    Text("GitHub Releases",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick={model.check()},enabled=!state.checking) {Text(if(state.checking) "检查中" else "检查更新")}
            }
            state.message?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}
            state.release?.let {release ->
                HorizontalDivider()
                Text("新版本 ${release.version}",style=MaterialTheme.typography.titleMedium)
                Text(release.notes.ifBlank {"此版本提供功能改进与问题修复。"},style=MaterialTheme.typography.bodyMedium)
                Text("${updateSize(release.size)} · 下载可能使用移动数据，漫游时等待网络",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                when(state.download.stage) {
                    UpdateDownloadStage.Downloading -> {
                        LinearProgressIndicator(progress={state.download.progress},modifier=Modifier.fillMaxWidth())
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(state.download.message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick=model::cancelDownload,enabled=!state.preparingInstall) {Text("取消下载")}
                        }
                        Text("可关闭此面板继续使用。未开启通知时，下载后回到此处安装。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    UpdateDownloadStage.Blocked -> {
                        Text(state.download.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                        Text("重复下载同一个安装包不能解决此问题，请等待发布兼容的新版本。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick=model::cancelDownload) {Text("清除此安装包")}
                    }
                    UpdateDownloadStage.Ready -> {
                        Text("安装包已下载并校验，无需重新下载",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                        Button(onClick=model::requestInstall,enabled=!state.preparingInstall,modifier=Modifier.fillMaxWidth()) {Text(if(state.preparingInstall) "校验中" else "立即安装")}
                    }
                    else -> {
                        if(state.download.stage==UpdateDownloadStage.Failed) Text(state.download.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                        Button(onClick={download()},enabled=!state.preparingInstall,modifier=Modifier.fillMaxWidth()) {AppIcon("download",modifier=Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(state.download.stage==UpdateDownloadStage.Failed) "重新下载" else "下载更新")}
                    }
                }
                if(state.download.stage==UpdateDownloadStage.Idle) TextButton(onClick=model::ignoreVersion) {Text("忽略此版本")}
            }
            HorizontalDivider()
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=12.dp)) {
                    Text("自动检查与提醒",style=MaterialTheme.typography.bodyLarge)
                    Text("启动时检查，每天最多一次；关闭后仍可手动检查",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.automatic,model::setAutomatic)
            }
            TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/$UPDATE_REPOSITORY/releases")))}.onFailure {model.installError("无法打开浏览器")}}) {Text("在 GitHub 查看发布记录")}
        }
    }
    if(allowInteraction && resumed && permissionDialog) AlertDialog(onDismissRequest={permissionDialog=false},title={Text("允许安装课页更新")},text={Text("需要在系统设置中允许课页安装应用。返回后会继续打开安装确认；不会自动确认安装，安装包也会保留。")},confirmButton={TextButton(onClick={
        permissionDialog=false
        runCatching {installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}
            .onFailure {model.installError("无法打开安装权限设置，请在系统设置中允许课页安装应用")}
    }) {Text("前往设置")}},dismissButton={TextButton(onClick={permissionDialog=false}) {Text("稍后")}})
}

private fun updateSize(size: Long)=String.format(Locale.ROOT,"%.1f MB",size/1024.0/1024.0)
