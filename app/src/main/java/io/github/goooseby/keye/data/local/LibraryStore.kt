package io.github.goooseby.keye.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.goooseby.keye.data.model.AppSettings
import io.github.goooseby.keye.data.model.Course
import io.github.goooseby.keye.data.model.StudyMaterial
import io.github.goooseby.keye.data.model.TaskRecord
import org.json.JSONObject

class LibraryStore private constructor(context: Context) : SQLiteOpenHelper(context, "keye-library.sqlite", null, 1) {
    companion object {
        @Volatile private var instance: LibraryStore? = null
        fun getInstance(context: Context): LibraryStore = instance ?: synchronized(this) {
            instance ?: LibraryStore(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE courses (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE materials (id TEXT PRIMARY KEY, source_key TEXT UNIQUE NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("尚未定义从 $oldVersion 到 $newVersion 的资料库迁移")
    }

    private fun all(table: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT data FROM $table ORDER BY rowid", null).use { cursor ->
            while (cursor.moveToNext()) result += JSONObject(cursor.getString(0))
        }
        return result
    }

    fun courses(): List<Course> = all("courses").map(Course::from)
    fun materials(): List<StudyMaterial> = all("materials").map(StudyMaterial::from)
    fun tasks(): List<TaskRecord> = all("tasks").map(TaskRecord::from)

    fun material(id: String): StudyMaterial? =
        readableDatabase.rawQuery("SELECT data FROM materials WHERE id=?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) StudyMaterial.from(JSONObject(cursor.getString(0))) else null
        }

    fun materialBySource(key: String): StudyMaterial? =
        readableDatabase.rawQuery("SELECT data FROM materials WHERE source_key=?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) StudyMaterial.from(JSONObject(cursor.getString(0))) else null
        }

    fun put(course: Course) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO courses(id,data) VALUES(?,?)",
            arrayOf(course.id, course.json().toString())
        )
    }

    fun put(material: StudyMaterial) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO materials(id,source_key,data) VALUES(?,?,?)",
            arrayOf(material.id, material.sourceKey, material.json().toString())
        )
    }

    fun put(task: TaskRecord) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO tasks(id,data) VALUES(?,?)",
            arrayOf(task.id, task.json().toString())
        )
    }

    fun putTasks(tasks: List<TaskRecord>) {
        val db=writableDatabase
        db.beginTransaction()
        try {tasks.forEach(::put);db.setTransactionSuccessful()} finally {db.endTransaction()}
    }

    fun settings(): AppSettings =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key='app'", null).use { cursor ->
            if (cursor.moveToFirst()) AppSettings.from(JSONObject(cursor.getString(0))) else AppSettings()
        }

    fun put(settings: AppSettings) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO settings(key,value) VALUES('app',?)",
            arrayOf(settings.json().toString())
        )
    }
}
