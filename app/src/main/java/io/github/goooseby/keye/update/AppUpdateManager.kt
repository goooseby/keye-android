package io.github.goooseby.keye.update

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import org.json.JSONObject

internal data class AppRelease(val assetId: Long,val version: String,val url: String,val size: Long,val sha256: String,val notes: String) {
    fun json()=JSONObject().put("id",assetId).put("version",version).put("url",url).put("size",size).put("sha256",sha256).put("notes",notes).toString()
    companion object {
        fun fromStored(json: String): AppRelease {
            val obj=JSONObject(json)
            return checked(obj.getLong("id"),obj.getString("version"),obj.getString("url"),obj.getLong("size"),obj.getString("sha256"),obj.optString("notes"))
        }
        fun checked(id: Long,version: String,url: String,size: Long,sha: String,notes: String): AppRelease {
            require(id>0 && StableVersion.parse(version)!=null && validUpdateUrl(url)) {"更新版本信息不正确"}
            require(size in 1..512L*1024*1024 && Regex("[a-fA-F0-9]{64}").matches(sha)) {"安装包缺少有效大小或 SHA-256 摘要"}
            return AppRelease(id,version.removePrefix("v"),url,size,sha.lowercase(),notes.take(12_000))
        }
    }
}
internal enum class UpdateDownloadStage { Idle, Downloading, Ready, Failed, Blocked }
private class UpdatePackageIncompatible(message: String): IllegalStateException(message)
internal data class UpdateDownload(val stage: UpdateDownloadStage=UpdateDownloadStage.Idle,val progress: Float=0f,val message: String="",val file: File?=null)

@Suppress("DEPRECATION")
internal class AppUpdateManager(private val context: Context) {
    val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val packages=context.packageManager
    val installed: PackageInfo=packages.getPackageInfo(context.packageName,signingFlags())
    val currentVersion: String=installed.versionName ?: "0.0.0"
    val currentCode: Long=versionCode(installed)
    private var verifiedIdentity:String?=null
    private var failedValidationIdentity:String?=null
    private var failedValidationMessage:String?=null
    private var failedValidationStage=UpdateDownloadStage.Failed
    private val privateDir get()=File(context.filesDir,"updates").apply {check(exists()||mkdirs()) {"无法创建更新目录"}}
    private fun verifiedFile(release: AppRelease)=File(privateDir,"asset-${release.assetId}.apk")
    private fun downloadedFile(release: AppRelease)=File(context.getExternalFilesDir("updates") ?: error("下载存储暂不可用"),"asset-${release.assetId}.apk")
    fun savedRelease(): AppRelease?=prefs.getString("download_release",null)?.let {runCatching {AppRelease.fromStored(it)}.getOrNull()}
    fun autoEnabled()=prefs.getBoolean("auto_enabled",true)
    fun autoInstall()=prefs.getBoolean("install_when_ready",false)
    fun consumeAutoInstall() {prefs.edit().putBoolean("install_when_ready",false).commit()}
    fun setAutoEnabled(value: Boolean) {prefs.edit().putBoolean("auto_enabled",value).apply()}
    fun ignore(version: String) {prefs.edit().putString("ignored_version",version).apply()}
    fun ignored(version: String)=prefs.getString("ignored_version",null)==version

    fun fetchLatest(manual: Boolean): AppRelease? {
        val today=LocalDate.now()
        if(!manual && !shouldCheckUpdateToday(today,prefs.getString("last_attempt_day",null),autoEnabled())) return cachedLatest()
        // Persist the attempt before I/O, including failed requests and process death.
        check(prefs.edit().putString("last_attempt_day",today.toString()).commit()) {"无法保存更新检查时间"}
        val connection=URL("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=10_000;connection.readTimeout=10_000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("User-Agent","Keye-Android/$currentVersion")
            val cached=prefs.getString("release_cache",null)
            if(cached!=null) prefs.getString("release_etag",null)?.let {connection.setRequestProperty("If-None-Match",it)}
            val body=when(val status=connection.responseCode) {
                304 ->cached ?: error("更新缓存不可用，请重试")
                404 ->{prefs.edit().remove("release_cache").remove("release_etag").apply();return null}
                200 -> {
                    val text=connection.inputStream.use {input ->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            val count=input.read(buffer);if(count<0) break
                            require(output.size()+count<=1024*1024) {"更新信息过大"}
                            output.write(buffer,0,count)
                        }
                        output.toString("UTF-8")
                    }
                    prefs.edit().putString("release_cache",text).putString("release_etag",connection.getHeaderField("ETag")).apply()
                    text
                }
                403,429 ->error("GitHub 暂时限制了检查频率，请稍后再试")
                else ->error("无法获取更新信息（HTTP $status），请稍后重试")
            }
            return parseRelease(body)
        } finally {connection.disconnect()}
    }

    private fun cachedLatest(): AppRelease?=prefs.getString("release_cache",null)?.let {runCatching {parseRelease(it)}.getOrNull()}
    private fun parseRelease(body: String): AppRelease? {
        val obj=JSONObject(body)
        if(obj.optBoolean("draft")||obj.optBoolean("prerelease")) return null
        val tag=obj.getString("tag_name")
        val remote=StableVersion.parse(tag) ?: error("正式版本标签应使用 v主版本.次版本.修订号")
        if(remote<=(StableVersion.parse(currentVersion) ?: error("当前应用版本号不正确"))) return null
        val assets=obj.getJSONArray("assets")
        val candidates=(0 until assets.length()).map {assets.getJSONObject(it)}.filter {it.optString("name")==UPDATE_APK_NAME && it.optString("state")=="uploaded"}
        require(candidates.size==1) {"该版本尚未提供完整的 Android 安装包"}
        val asset=candidates.single()
        val digest=asset.optString("digest")
        require(digest.startsWith("sha256:")) {"该版本安装包缺少 GitHub SHA-256 摘要"}
        return AppRelease.checked(asset.getLong("id"),tag,asset.getString("browser_download_url"),asset.getLong("size"),digest.removePrefix("sha256:"),obj.optString("body"))
    }

    fun startDownload(release: AppRelease) {
        val existing=savedRelease()
        if(existing?.assetId==release.assetId) {
            val snapshot=refresh(release)
            if(snapshot.stage in setOf(UpdateDownloadStage.Ready,UpdateDownloadStage.Downloading)) {
                prefs.edit().putBoolean("install_when_ready",true).commit();return
            }
        }
        cancelDownload()
        val target=downloadedFile(release)
        if(target.exists()) check(target.delete()) {"无法清理不完整的安装包"}
        val request=DownloadManager.Request(Uri.parse(release.url))
            .setTitle("华工课栈 ${release.version}").setDescription("正在下载应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context,"updates",target.name)
        check(prefs.edit().putString("download_release",release.json()).putBoolean("install_when_ready",true).remove("download_id").commit())
        val id=downloads.enqueue(request)
        check(prefs.edit().putLong("download_id",id).commit()) {"下载已开始，但未能保存任务信息"}
    }

    fun refresh(release: AppRelease): UpdateDownload {
        if(StableVersion.parse(release.version)!!<=StableVersion.parse(currentVersion)!!) {
            cancelDownload();return UpdateDownload()
        }
        val ready=verifiedFile(release)
        if(ready.exists()) {
            try {validate(ready,release);return UpdateDownload(UpdateDownloadStage.Ready,1f,"已下载，随时可安装",ready)}
            catch(_: Exception) {ready.delete();verifiedIdentity=null}
        }
        if(savedRelease()?.assetId!=release.assetId) return UpdateDownload()
        var id=prefs.getLong("download_id",-1)
        if(id<0) {
            // Recover the small enqueue/persist crash window from our own jobs.
            downloads.query(DownloadManager.Query())?.use {cursor ->
                while(cursor.moveToNext()) if(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))==release.url) {
                    id=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
            if(id>=0) prefs.edit().putLong("download_id",id).commit()
        }
        val cursor=downloads.query(DownloadManager.Query().setFilterById(id)) ?: return UpdateDownload(UpdateDownloadStage.Failed,message="下载记录不可用，请重试")
        cursor.use {
            if(!it.moveToFirst()) return UpdateDownload(UpdateDownloadStage.Failed,message="下载已被移除，可重新下载")
            val status=it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done=it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val progress=(done.toFloat()/release.size).coerceIn(0f,1f)
            return when(status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val source=downloadedFile(release)
                    val sourceIdentity="${release.assetId}:${release.sha256}:${source.length()}:${source.lastModified()}"
                    if(sourceIdentity==failedValidationIdentity) return UpdateDownload(failedValidationStage,message=failedValidationMessage ?: "安装包校验失败，请重新下载")
                    try {
                        check(source.isFile && source.length()==release.size) {"安装包不完整，请重新下载"}
                        // Verify a private copy so external storage cannot change
                        // between validation and the package installer's read.
                        val staged=File(privateDir,"asset-${release.assetId}.part")
                        try {
                            source.inputStream().use {input ->staged.outputStream().use {output ->
                                val buffer=ByteArray(64*1024);var total=0L
                                while(true) {
                                    val count=input.read(buffer);if(count<0) break
                                    total+=count;check(total<=release.size) {"安装包大小异常，请重新下载"}
                                    output.write(buffer,0,count)
                                }
                            }}
                            validate(staged,release)
                            check(staged.renameTo(ready)) {"无法保存已校验的安装包"}
                            verifiedIdentity=null;validate(ready,release)
                        } finally {staged.delete()}
                        UpdateDownload(UpdateDownloadStage.Ready,1f,"已下载，随时可安装",ready)
                    } catch(error: Exception) {
                        failedValidationIdentity=sourceIdentity
                        failedValidationMessage=error.message ?: "安装包校验失败，请重新下载"
                        failedValidationStage=if(error is UpdatePackageIncompatible) UpdateDownloadStage.Blocked else UpdateDownloadStage.Failed
                        UpdateDownload(failedValidationStage,message=failedValidationMessage!!)
                    }
                }
                DownloadManager.STATUS_FAILED ->UpdateDownload(UpdateDownloadStage.Failed,message=when(it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE ->"存储空间不足，请释放空间后重试"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND ->"下载存储暂不可用，请重试"
                    else ->"下载失败，请检查网络后重试"
                })
                DownloadManager.STATUS_PAUSED ->UpdateDownload(UpdateDownloadStage.Downloading,progress,"等待网络或系统继续下载")
                else ->UpdateDownload(UpdateDownloadStage.Downloading,progress,if(done>0) "下载中 ${(progress*100).toInt()}%" else "等待系统开始下载")
            }
        }
    }

    private fun validate(file: File,release: AppRelease,force: Boolean=false) {
        val identity="${release.version}:${release.assetId}:${release.sha256}:${file.path}:${file.length()}:${file.lastModified()}:$currentCode"
        if(!force && identity==verifiedIdentity) return
        require(file.length()==release.size) {"安装包大小不符，请重新下载"}
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input ->val buffer=ByteArray(64*1024);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}}
        val hash=digest.digest().joinToString("") {"%02x".format(it.toInt() and 0xff)}
        require(hash==release.sha256) {"安装包校验失败，请重新下载"}
        val candidate=packages.getPackageArchiveInfo(file.path,signingFlags()) ?: error("安装包无法解析")
        if(candidate.packageName!=context.packageName) throw UpdatePackageIncompatible("安装包不属于华工课栈，已停止安装")
        if(candidate.versionName!=release.version || versionCode(candidate)<=currentCode) throw UpdatePackageIncompatible("安装包版本不符合更新要求")
        if(candidate.applicationInfo?.minSdkVersion?.let {it<=Build.VERSION.SDK_INT}!=true) throw UpdatePackageIncompatible("该版本不支持当前 Android 系统")
        if(!matchingUpdateSigners(signers(installed),signers(candidate))) throw UpdatePackageIncompatible("安装包签名与当前应用不一致，无法覆盖安装")
        verifiedIdentity=identity
    }

    fun installerIntent(release: AppRelease): Intent {
        val file=verifiedFile(release)
        validate(file,release,force=true)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {clipData=android.content.ClipData.newRawUri("Keye update",uri)}
    }

    fun cancelDownload() {
        val release=savedRelease()
        val id=prefs.getLong("download_id",-1)
        if(id>=0) downloads.remove(id)
        release?.let {runCatching {downloadedFile(it).delete();verifiedFile(it).delete()}}
        prefs.edit().remove("download_id").remove("download_release").putBoolean("install_when_ready",false).commit()
        context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION_ID)
        verifiedIdentity=null;failedValidationIdentity=null;failedValidationMessage=null
    }

    private fun signingFlags()=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    private fun versionCode(info: PackageInfo)=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
    private fun signers(info: PackageInfo): Set<String> {
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map {it.toCharsString()}.toSet()
    }
}
