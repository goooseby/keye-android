package io.github.goooseby.keye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.goooseby.keye.data.Course
import io.github.goooseby.keye.data.ScheduleEntry
import io.github.goooseby.keye.data.StudyMaterial
import io.github.goooseby.keye.data.TaskRecord
import io.github.goooseby.keye.ui.AcquireScreen
import io.github.goooseby.keye.ui.CourseScreen
import io.github.goooseby.keye.ui.LibraryScreen
import io.github.goooseby.keye.ui.SettingsScreen
import io.github.goooseby.keye.ui.TasksScreen
import io.github.goooseby.keye.ui.TimetableScreen
import io.github.goooseby.keye.network.SchoolConnection
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.KeyeTheme
import io.github.goooseby.keye.ui.reader.ReaderScreen
import java.io.File

/** Debug-only visual fixtures; this activity is absent from release builds. */
class UiGalleryActivity: ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if(intent.getStringExtra("command")=="stop-login-renderer" && android.os.Build.VERSION.SDK_INT>=29) {
            fun find(view: android.view.View): android.webkit.WebView? = if(view is android.webkit.WebView) view
                else (view as? android.view.ViewGroup)?.let {group ->(0 until group.childCount).firstNotNullOfOrNull {find(group.getChildAt(it))}}
            find(window.decorView)?.webViewRenderProcess?.terminate()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        val page=intent.getStringExtra("page")?:"library"
        if(page=="launcher-icons") {
            setContentView(LauncherIconPreview(this))
            return
        }
        setContent {KeyeTheme {
            val model: KeyeViewModel=viewModel()
            val actual by model.state.collectAsState()
            if(page=="login-live") {
                io.github.goooseby.keye.ui.SchoolLoginScreen(model,{
                    File(filesDir,"qa/login-live-check.json").apply {parentFile?.mkdirs()}.writeText(org.json.JSONObject()
                        .put("attempt",intent.getStringExtra("attempt"))
                        .put("connected",model.state.value.schoolConnection==SchoolConnection.Connected)
                        .put("loggedIn",model.state.value.loggedIn).toString())
                    finish()
                },automaticVerification=intent.getBooleanExtra("automatic",true))
                return@KeyeTheme
            }
            if(page=="login-error") {
                io.github.goooseby.keye.ui.SchoolLoginScreen(model,{finish()},initialPage=intent.getStringExtra("url") ?: "https://127.0.0.1:1/",verifySession=false)
                return@KeyeTheme
            }
            if(page=="offline-timetable") {
                // Real persisted data, deliberately without authenticating or changing WebView cookies.
                var opened by remember {mutableStateOf<String?>(null)}
                androidx.activity.compose.BackHandler(opened!=null) {opened=null}
                Scaffold(topBar={Text("离线课表检查",Modifier.statusBarsPadding().padding(20.dp),style=MaterialTheme.typography.titleLarge)}) {padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        val material=actual.materials.firstOrNull {it.id==opened}
                        if(material!=null) ReaderScreen(material,model,{File(filesDir,it)},{opened=null},{},{},null)
                        else TimetableScreen(actual.copy(schoolConnection=SchoolConnection.Expired),model,{},
                            {entry ->opened=actual.materials.firstOrNull {it.sourceKey==entry.id && !it.deleted && it.pages.isNotEmpty()}?.id},{})
                    }
                }
                return@KeyeTheme
            }
            val courses=listOf(Course("visual-math","高等数学","2026 秋季"),Course("visual-net","计算机网络","2026 秋季"),Course("visual-algo","数据结构与算法","专业课程"),Course("visual-english","大学英语","通识课程"))
            val names=listOf("第三章 · 微分与导数","第二章 · 网络体系结构","线性表与链式存储")
            val requested=intent.getStringExtra("material")
            val materials=if(requested!=null) actual.materials.filter {it.id==requested} else actual.materials.filterNot(StudyMaterial::deleted).sortedWith(compareBy<StudyMaterial>{if(it.kind=="pdf") 0 else 1}.thenBy{it.pages.size}).take(3).mapIndexed{i,m ->m.copy(title=names[i],courseId=courses[0].id,note=if(i==0) "课堂重点与例题" else "",day="2026-11-02")}
            val entries=listOf(ScheduleEntry("visual-1","visual-math","高等数学","2026-11-02","1","1"),ScheduleEntry("visual-2","visual-math","高等数学","2026-10-30","1","2"),ScheduleEntry("visual-3","visual-net","计算机网络","2026-11-01","2","3"))
            val now=System.currentTimeMillis()
            val tasks=listOf(TaskRecord(id="visual-run",type="download",title="计算机网络 · 第三讲",status="running",progress=64,message="已下载 32 / 50 页",updatedAt=now),TaskRecord(id="visual-error",type="download",title="高等数学 · 微分与导数",status="failed",message="网络连接中断，请检查网络后重试",updatedAt=now-1000),TaskRecord(id="visual-done",type="export",title="数据结构与算法",status="done",payload="content://visual",updatedAt=now-2000),TaskRecord(id="visual-wait",type="import",title="线性表与链式存储.pdf",status="queued",message="等待导入",updatedAt=now-3000))
            val state=actual.copy(courses=courses,materials=materials,schedule=entries,tasks=tasks,loggedIn=true)
            Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(when(page){"acquire" ->"获取课件";"tasks" ->"任务";"settings" ->"设置";"course" ->"高等数学";"reader" ->"第三章 · 微分与导数";else ->"资料库"},style=MaterialTheme.typography.titleLarge)
                }
            },bottomBar={if(page !in setOf("reader","course")) NavigationBar(containerColor=MaterialTheme.colorScheme.surface){listOf("资料" to "library","获取" to "download","任务" to "tasks","设置" to "settings").forEachIndexed{i,(label,icon) ->NavigationBarItem(selected=(when(page){"acquire" ->1;"tasks" ->2;"settings" ->3;else ->0})==i,onClick={},icon={AppIcon(icon)},label={Text(label,style=MaterialTheme.typography.labelMedium)})}}}) {padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when(page) {
                        "acquire" ->AcquireScreen(state,model,{},{})
                        "tasks" ->TasksScreen(tasks,model,{},{})
                        "settings" ->SettingsScreen(state,model,{})
                        "course" ->CourseScreen(courses[0],courses,materials,{},{},{_,_ ->},{_,_ ->},{File(filesDir,it)},model)
                        "reader" ->materials.firstOrNull()?.let{ReaderScreen(it,model,{path ->File(filesDir,path)},{},{},{},null)}
                        "empty" ->LibraryScreen(UiState(),{},{},{},{_,_ ->},{_,_ ->},{File(filesDir,it)},model)
                        else ->LibraryScreen(state,{},{},{},{_,_ ->},{_,_ ->},{File(filesDir,it)},model)
                    }
                }
            }
        }}
    }
}
