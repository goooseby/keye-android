package io.github.goooseby.keye

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

// Export ranges are independent of reading preferences and original documents.
enum class ExportAction { Save, Share, SaveAndShare }
data class ExportRequest(val ids: List<String>,val name: String,val pages: Map<String,Set<Int>>?=null,val action: ExportAction=ExportAction.Save,val origin: String="",val includeNotes: Boolean=true,val snapshots: List<StudyMaterial>?=null)
data class ExportResult(val uri: Uri,val name: String,val location: String,val action: ExportAction,val mime: String="application/pdf")
data class ExportState(val running: Boolean=false,val progress: Float=0f,val message: String="",val failed: Boolean=false,val retryable: Boolean=false,val result: ExportResult?=null,val autoShare: Boolean=false,val origin: String="")

fun parsePageRange(text: String,count: Int): Set<Int> {
    require(text.isNotBlank()) { "请输入页码，例如 1,3-5" }
    val result=sortedSetOf<Int>()
    text.replace('，',',').split(',').forEach { part ->
        val match=requireNotNull(Regex("""^\s*(\d+)\s*(?:-\s*(\d+)\s*)?$""").matchEntire(part)) {"页码格式不正确，例如 1,3-5"}
        val first=requireNotNull(match.groupValues[1].toIntOrNull()) {"页码过大"}
        val last=match.groupValues[2].takeIf { it.isNotEmpty() }?.let {requireNotNull(it.toIntOrNull()) {"页码过大"}} ?: first
        require(first in 1..count && last in first..count) { "页码应在 1–$count 之间，范围需从小到大" }
        result.addAll(first..last)
    }
    return result
}

class ExportStorage(private val context: Context) {
    private val prefs=context.getSharedPreferences("reading",Context.MODE_PRIVATE)
    val folder: Uri? get()=prefs.getString("exportFolder",null)?.let(Uri::parse)
    val location: String get()=prefs.getString("exportLabel",null) ?: if(Build.VERSION.SDK_INT>=29) "下载 / Keye" else "选择导出文件夹"
    fun setFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val document=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
        val label=context.contentResolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "所选文件夹"
        prefs.edit().putString("exportFolder",uri.toString()).putString("exportLabel",label).apply()
    }
    fun resetFolder() { prefs.edit().remove("exportFolder").remove("exportLabel").apply() }
    fun finish(staged: File,request: ExportRequest,checkActive: () -> Unit = {}): ExportResult {
        val base=request.name.trim().replace(Regex("""(?i)\.pdf$"""),"").replace(Regex("""[\/:*?"<>|\p{Cntrl}]"""),"_").take(100).ifBlank { "课件" }
        val filename="$base.pdf"
        if(request.action==ExportAction.Share) {
            val destination=File(File(staged.parentFile,java.util.UUID.randomUUID().toString()).apply {mkdirs()},filename)
            check(staged.renameTo(destination)) { "无法准备分享文件" }
            return ExportResult(FileProvider.getUriForFile(context,"${context.packageName}.files",destination),filename,"临时分享文件",request.action)
        }
        val resolver=context.contentResolver
        var created: Uri?=null
        try {
            val tree=folder
            var actualName=filename
            if(tree!=null) {
                val id=DocumentsContract.getTreeDocumentId(tree)
                val parent=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
                val names=mutableSetOf<String>()
                resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { c -> while(c.moveToNext()) names+=c.getString(0) }
                var suffix=1
                while(actualName in names) { actualName="$base (${suffix++}).pdf" }
                created=DocumentsContract.createDocument(resolver,parent,"application/pdf",actualName) ?: error("文件夹不可写，请更改导出位置")
            } else {
                if(Build.VERSION.SDK_INT<29) error("请先选择导出文件夹")
                val collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val names=mutableSetOf<String>()
                resolver.query(collection,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),"${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf("Download/Keye/"),null)?.use { c -> while(c.moveToNext()) names+=c.getString(0) }
                var suffix=1
                while(actualName in names) actualName="$base (${suffix++}).pdf"
                created=resolver.insert(collection,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,actualName);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Keye/");put(MediaStore.MediaColumns.IS_PENDING,1)
                }) ?: error("无法创建 PDF，请检查存储空间")
            }
            resolver.openOutputStream(created,"w")?.use { output -> staged.inputStream().use { input ->
                val buffer=ByteArray(64*1024)
                while(true) {checkActive();val size=input.read(buffer);if(size<0) break;output.write(buffer,0,size)}
            } } ?: error("无法写入，请更改导出位置")
            if(tree==null && Build.VERSION.SDK_INT>=29) resolver.update(created,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            return ExportResult(created,actualName,location,request.action)
        } catch(error: Exception) {
            created?.let { runCatching { if(folder!=null) DocumentsContract.deleteDocument(resolver,it) else resolver.delete(it,null,null) } }
            throw error
        } finally { staged.delete() }
    }
    fun share(result: ExportResult) {
        val send=Intent(Intent.ACTION_SEND).apply {
            type=result.mime;putExtra(Intent.EXTRA_STREAM,result.uri);putExtra(Intent.EXTRA_TITLE,result.name)
            clipData=android.content.ClipData.newUri(context.contentResolver,result.name,result.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send,if(result.mime=="application/pdf") "分享 PDF" else "分享当前页").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun open(result: ExportResult) { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(result.uri,result.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun cleanExpiredShares() { File(context.cacheDir,"shared").listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>7L*24*60*60*1000 }?.forEach { if(it.isDirectory) it.deleteRecursively() else it.delete() } }
}
