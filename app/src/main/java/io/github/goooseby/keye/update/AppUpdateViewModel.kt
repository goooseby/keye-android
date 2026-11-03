package io.github.goooseby.keye.update


import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AppUpdateState(
    val version: String="",val automatic: Boolean=true,val checking: Boolean=false,
    val release: AppRelease?=null,val download: UpdateDownload=UpdateDownload(),
    val panelOpen: Boolean=false,val prompt: Boolean=false,val message: String?=null,
    val preparingInstall: Boolean=false,val autoInstallPending: Boolean=false,val installer: Intent?=null
)

internal class AppUpdateViewModel(application: Application): AndroidViewModel(application) {
    private val manager=AppUpdateManager(application)
    private val lock=Mutex()
    private val mutable=MutableStateFlow(AppUpdateState(version=manager.currentVersion,automatic=manager.autoEnabled()))
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {lock.withLock {
                    manager.savedRelease()?.let {release ->
                        val download=manager.refresh(release)
                        if(download.stage!=UpdateDownloadStage.Idle) mutable.update {it.copy(release=release,download=download,autoInstallPending=manager.autoInstall())}
                    }
                }}
            } catch(error: CancellationException) {throw error}
            catch(_: Exception) { /* Recovery is retried from the update page. */ }
            check(manual=false)
        }
    }
    fun markPromptShown() {manager.prefs.edit().putString("last_prompt_day",LocalDate.now().toString()).apply()}
    fun showDetails() {mutable.update {it.copy(panelOpen=true,prompt=false)}}
    fun dismiss() {mutable.update {it.copy(panelOpen=false,prompt=false,message=null)}}
    fun ignoreVersion() {state.value.release?.let {manager.ignore(it.version)};dismiss()}
    fun setAutomatic(value: Boolean) {manager.setAutoEnabled(value);mutable.update {it.copy(automatic=value,prompt=if(value) it.prompt else false)}}
    fun check(manual: Boolean=true) {
        if(state.value.checking) return
        mutable.update {it.copy(checking=true,message=null)}
        viewModelScope.launch {
            try {
                val release=withContext(Dispatchers.IO) {lock.withLock {manager.fetchLatest(manual)}}
                mutable.update {old ->
                    val pinned=old.download.stage in setOf(UpdateDownloadStage.Downloading,UpdateDownloadStage.Ready)
                    val chosen=if(pinned) old.release else release
                    val canPrompt=!manual && chosen!=null && manager.autoEnabled() && !manager.ignored(chosen.version) &&
                        manager.prefs.getString("last_prompt_day",null)!=LocalDate.now().toString()
                    old.copy(checking=false,release=chosen,
                        download=if(pinned || chosen?.assetId==old.release?.assetId) old.download else UpdateDownload(),
                        prompt=canPrompt && old.download.stage==UpdateDownloadStage.Idle,
                        message=if(manual && chosen==null) "当前没有可安装的新版本" else null)
                }
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {mutable.update {it.copy(checking=false,message=if(manual) error.message ?: "无法检查更新，请稍后重试" else null)}}
        }
    }
    fun startDownload() {
        val release=state.value.release ?: return
        mutable.update {it.copy(prompt=false,panelOpen=true,message=null)}
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {lock.withLock {manager.startDownload(release)}}
                refreshDownload()
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {mutable.update {it.copy(message=error.message ?: "无法开始下载")}}
        }
    }
    suspend fun refreshDownload() {
        try {
            withContext(Dispatchers.IO) {lock.withLock {
                val release=state.value.release ?: manager.savedRelease() ?: return@withLock
                val snapshot=manager.refresh(release)
                mutable.update {it.copy(release=release,download=snapshot,autoInstallPending=manager.autoInstall())}
            }}
        } catch(error: CancellationException) {throw error}
        catch(error: Exception) {mutable.update {it.copy(download=UpdateDownload(UpdateDownloadStage.Failed,message=error.message ?: "无法读取下载状态"))}}
    }
    fun cancelDownload() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {lock.withLock {manager.cancelDownload()}}
                mutable.update {it.copy(download=UpdateDownload(),autoInstallPending=false,preparingInstall=false,installer=null)}
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {mutable.update {it.copy(message=error.message ?: "无法取消下载，请稍后重试")}}
        }
    }
    fun requestInstall() {
        if(state.value.preparingInstall || state.value.installer!=null) return
        val release=state.value.release ?: return
        mutable.update {it.copy(preparingInstall=true,message=null,prompt=false)}
        viewModelScope.launch {
            try {
                val intent=withContext(Dispatchers.IO) {lock.withLock {
                    manager.consumeAutoInstall()
                    val snapshot=manager.refresh(release)
                    mutable.update {it.copy(download=snapshot)}
                    check(snapshot.stage==UpdateDownloadStage.Ready) {snapshot.message.ifBlank {"安装包还未下载完成"}}
                    manager.installerIntent(release)
                }}
                mutable.update {it.copy(preparingInstall=false,autoInstallPending=false,installer=intent)}
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {mutable.update {it.copy(preparingInstall=false,autoInstallPending=false,panelOpen=true,message=error.message ?: "无法打开安装包")}}
        }
    }
    fun consumeInstaller() {
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(UPDATE_NOTIFICATION_ID)
        mutable.update {it.copy(installer=null,panelOpen=true)}
    }
    fun installError(message: String) {mutable.update {it.copy(message=message,panelOpen=true)}}
}
