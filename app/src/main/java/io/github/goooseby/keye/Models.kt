package io.github.goooseby.keye

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")
internal fun JSONObject.textOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
internal fun List<String>.asJson(): JSONArray = JSONArray().also { result ->
    forEach { result.put(it) }
}

data class Course(
    val id: String = newId(),
    val title: String,
    val term: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("term", term).put("createdAt", createdAt)
    companion object {
        fun from(value: JSONObject) = Course(
            value.getString("id"), value.optString("title"), value.optString("term"),
            value.optLong("createdAt")
        )
    }
}

data class PageFile(
    val source: String,
    val preview: String,
    val pdfPage: Int? = null,
    val remoteKey: String? = null
) {
    fun json() = JSONObject().put("source", source).put("preview", preview).put("pdfPage", pdfPage).put("remoteKey",remoteKey)
    companion object {
        fun from(value: JSONObject) = PageFile(
            value.getString("source"), value.getString("preview"),
            if (value.isNull("pdfPage")) null else value.getInt("pdfPage"),value.textOrNull("remoteKey")
        )
    }
}

data class StudyMaterial(
    val id: String = newId(),
    val sourceKey: String,
    val title: String,
    val day: String,
    val courseId: String? = null,
    val note: String = "",
    val kind: String,
    val pages: List<PageFile>,
    val excluded: Set<Int> = emptySet(),
    val revision: Int = 0,
    val reviewRevision: Int? = null,
    val exportRevision: Int? = null,
    val exportUri: String? = null,
    val lastPage: Int = 1,
    val readAt: Long = 0,
    val readOffset: Int = 0,
    val readFraction: Float = 0f,
    val bookmarks: Set<Int> = emptySet(),
    val deleted: Boolean = false,
    val schoolEntry: ScheduleEntry? = null,
    val pageNotes: Map<Int,String> = emptyMap(),
    val touched: Long = System.currentTimeMillis()
) {
    val keptCount get() = pages.size - excluded.size
    val reviewed get() = reviewRevision == revision
    val exported get() = exportRevision == revision && exportUri != null
    fun json() = JSONObject()
        .put("id", id).put("sourceKey", sourceKey).put("title", title).put("day", day)
        .put("courseId", courseId).put("note", note).put("kind", kind)
        .put("pages", JSONArray().also { array -> pages.forEach { array.put(it.json()) } })
        .put("excluded", JSONArray().also { array -> excluded.sorted().forEach(array::put) })
        .put("revision", revision).put("reviewRevision", reviewRevision)
        .put("exportRevision", exportRevision).put("exportUri", exportUri)
        .put("lastPage", lastPage).put("readAt", readAt).put("readOffset", readOffset).put("readFraction", readFraction.toDouble())
        .put("bookmarks", JSONArray(bookmarks.sorted())).put("deleted", deleted).put("touched", touched)
        .put("schoolEntry",schoolEntry?.json())
        .put("pageNotes",JSONObject().also {obj ->pageNotes.forEach {(page,text) ->obj.put(page.toString(),text)}})

    companion object {
        fun from(value: JSONObject): StudyMaterial {
            val items = value.getJSONArray("pages")
            val excluded = value.optJSONArray("excluded") ?: JSONArray()
            return StudyMaterial(
                id = value.getString("id"),
                sourceKey = value.getString("sourceKey"),
                title = value.optString("title"),
                day = value.optString("day"),
                courseId = value.textOrNull("courseId"),
                note = value.optString("note"),
                kind = value.optString("kind"),
                pages = (0 until items.length()).map { PageFile.from(items.getJSONObject(it)) },
                excluded = (0 until excluded.length()).map { excluded.getInt(it) }.toSet(),
                revision = value.optInt("revision"),
                reviewRevision = if (value.isNull("reviewRevision")) null else value.getInt("reviewRevision"),
                exportRevision = if (value.isNull("exportRevision")) null else value.getInt("exportRevision"),
                exportUri = value.textOrNull("exportUri"),
                lastPage = value.optInt("lastPage", 1),
                readAt = value.optLong("readAt", 0),
                readOffset = value.optInt("readOffset", 0),
                readFraction = value.optDouble("readFraction",0.0).toFloat().coerceIn(0f,.999f),
                bookmarks = value.optJSONArray("bookmarks")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                deleted = value.optBoolean("deleted"),
                schoolEntry=value.optJSONObject("schoolEntry")?.let {ScheduleEntry.from(it)},
                pageNotes=value.optJSONObject("pageNotes")?.let {obj ->obj.keys().asSequence().mapNotNull {key ->key.toIntOrNull()?.takeIf {it>0}?.let {it to obj.optString(key).take(2000)}}.toMap()} ?: emptyMap(),
                touched = value.optLong("touched")
            )
        }
    }
}

data class ScheduleEntry(
    val id: String,
    val groupId: String,
    val title: String,
    val day: String,
    val courseId: String,
    val subId: String
) {
    fun json() = JSONObject().put("id", id).put("groupId", groupId).put("title", title)
        .put("day", day).put("courseId", courseId).put("subId", subId)
    companion object {
        fun from(value: JSONObject) = ScheduleEntry(
            value.getString("id"), value.getString("groupId"), value.getString("title"),
            value.getString("day"), value.getString("courseId"), value.getString("subId")
        )
    }
}

data class TaskRecord(
    val id: String = newId(),
    val type: String,
    val title: String,
    val status: String = "queued",
    val progress: Int = 0,
    val message: String = "",
    val payload: String = "",
    val materialId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val batchOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun json() = JSONObject().put("id", id).put("type", type).put("title", title)
        .put("status", status).put("progress", progress).put("message", message)
        .put("payload", payload).put("materialId", materialId).put("updatedAt", updatedAt)
        .put("createdAt", createdAt).put("batchOrder", batchOrder)
    companion object {
        fun from(value: JSONObject) = TaskRecord(
            id = value.getString("id"), type = value.optString("type"),
            title = value.optString("title"), status = value.optString("status"),
            progress = value.optInt("progress"), message = value.optString("message"),
            payload = value.optString("payload"), materialId = value.textOrNull("materialId"),
            createdAt = value.optLong("createdAt",value.optLong("updatedAt")),
            batchOrder = value.optInt("batchOrder",0),
            updatedAt = value.optLong("updatedAt")
        )
    }
}

data class AppSettings(
    val timeoutSeconds: Int = 30,
    val retryCount: Int = 3,
    val requestGapMillis: Long = 100
) {
    fun json() = JSONObject().put("timeoutSeconds", timeoutSeconds)
        .put("retryCount", retryCount).put("requestGapMillis", requestGapMillis)
    companion object {
        fun from(value: JSONObject) = AppSettings(
            value.optInt("timeoutSeconds", 30).coerceIn(5, 600),
            value.optInt("retryCount", 3).coerceIn(1, 10),
            value.optLong("requestGapMillis", 100).coerceIn(0, 5000)
        )
    }
}

/** Queue position never depends on mutable progress timestamps. */
val taskQueueOrder: Comparator<TaskRecord> = compareByDescending<TaskRecord> {it.createdAt}.thenBy {it.batchOrder}.thenBy {it.id}

fun mergeTaskBatch(existing: List<TaskRecord>,incoming: List<TaskRecord>): List<TaskRecord> {
    val replacements=incoming.associateBy {it.id}
    return (existing.filterNot {it.id in replacements}+replacements.values).sortedWith(taskQueueOrder)
}
