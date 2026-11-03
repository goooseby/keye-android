package io.github.goooseby.keye

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only controlled feed. No school cookies, endpoints or user data are used. */
class ClassroomDemoActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(savedInstanceState==null || !DemoClassroomFeed.initialized) DemoClassroomFeed.reset(application,intent.getIntExtra("pages",2))
        val factory=object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass: Class<T>): T=KeyeViewModel(application) {DemoClassroomFeed} as T
        }
        val model=ViewModelProvider(this,factory)[KeyeViewModel::class.java]
        if(model.state.value.classroom==null) model.startClassroom(DemoClassroomFeed.entry)
        setContent {KeyeTheme {
            val state by model.state.collectAsState()
            state.classroom?.let {session ->ClassroomScreen(session,state.materials.firstOrNull {it.id==session.materialId},state,model,{File(filesDir,it)},{finish()},{})}
        }}
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.hasExtra("pages")) DemoClassroomFeed.total=intent.getIntExtra("pages",2).coerceIn(0,20)
        if(intent.hasExtra("offline")) DemoClassroomFeed.offline=intent.getBooleanExtra("offline",false)
    }
}

private object DemoClassroomFeed: ClassroomPageSource {
    var initialized=false;private set
    lateinit var entry: ScheduleEntry;private set
    @Volatile var total=2
    @Volatile var offline=false
    private lateinit var trace: File
    private val events=JSONArray()
    private val active=AtomicInteger()
    private var maximum=0
    fun reset(app: Application,count: Int) {
        val id=newId()
        entry=ScheduleEntry("qa-classroom-$id","qa-classroom-course-$id","课堂跟看演示",LocalDate.now().toString(),"qa","qa")
        total=count.coerceIn(0,20);offline=false;initialized=true;maximum=0
        trace=File(app.filesDir,"qa/classroom-trace.json").apply {parentFile?.mkdirs()}
        synchronized(this) {while(events.length()>0) events.remove(0)}
    }
    private fun record(method: String,block: ()->Unit) {
        val concurrency=active.incrementAndGet()
        synchronized(this) {maximum=maxOf(maximum,concurrency);events.put(JSONObject().put("method",method).put("at",System.currentTimeMillis()).put("count",total));trace.writeText(JSONObject().put("maxConcurrent",maximum).put("entry",entry.json()).put("events",events).toString())}
        try {Thread.sleep(120);block()} finally {active.decrementAndGet()}
    }
    override fun owns(entry: ScheduleEntry)=entry.id==this.entry.id
    override fun pages(entry: ScheduleEntry): List<ClassroomPage> {
        record("catalog") {if(offline) throw java.io.IOException("演示网络暂不可用")}
        return (1..total).map {n ->val url="https://classroom-test.invalid/${entry.id}/page-$n.png";ClassroomPage(classroomPageKey(url),url)}
    }
    override fun image(page: ClassroomPage): ByteArray {
        record("image") {if(offline) throw java.io.IOException("演示网络暂不可用")}
        val number=page.url.substringAfterLast("page-").substringBefore('.').toInt()
        val bitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=Color.rgb(225,242,252);canvas.drawRect(0f,0f,1280f,200f,paint)
        paint.color=Color.rgb(0,101,138);paint.textSize=36f;canvas.drawText("KEYE / CLASSROOM DEMO",70f,95f,paint)
        paint.textSize=84f;canvas.drawText("PAGE $number",70f,330f,paint)
        paint.color=Color.rgb(80,96,111);paint.textSize=34f;canvas.drawText("New pages arrive without leaving this slide.",70f,420f,paint)
        paint.color=Color.rgb(114,81,172);canvas.drawRoundRect(70f,500f,1210f,610f,24f,24f,paint)
        paint.color=Color.WHITE;paint.textSize=36f;canvas.drawText("Follow latest  /  Browse earlier  /  Keep your notes",100f,568f,paint)
        return try {ByteArrayOutputStream().use {out ->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}} finally {bitmap.recycle()}
    }
}
