package io.github.goooseby.keye.announcements

import android.content.Context
import io.github.goooseby.keye.R
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

internal class AnnouncementRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app_announcements", Context.MODE_PRIVATE)
    private val endpoint = context.getString(R.string.announcements_url).trim()
    val configured get() = endpoint.isNotEmpty()

    fun cached(): List<Announcement> = runCatching {
        parseAnnouncements(prefs.getString("feed", null) ?: return emptyList())
    }.getOrDefault(emptyList())

    fun isRead(key: String) = prefs.getBoolean("read:$key", false)
    fun snoozedUntil(key: String) = prefs.getLong("snooze:$key", 0)
    fun markRead(key: String) { prefs.edit().putBoolean("read:$key", true).remove("snooze:$key").apply() }
    fun snooze(key: String, now: Long) { prefs.edit().putLong("snooze:$key", now + ANNOUNCEMENT_SNOOZE_MILLIS).apply() }

    /** Cached successes last six hours; failed automatic attempts back off for fifteen minutes. */
    fun refresh(manual: Boolean, now: Long): List<Announcement> {
        if (!configured) return cached()
        check(validAnnouncementsUrl(endpoint)) { "公告服务地址配置有误" }
        val checked = prefs.getLong("checked_at", 0)
        val attempted = prefs.getLong("attempted_at", 0)
        if (!manual && (now >= checked && now - checked < 6 * 60 * 60 * 1000L ||
                    now >= attempted && now - attempted < 15 * 60 * 1000L)) return cached()
        prefs.edit().putLong("attempted_at", now).apply()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "暂时无法获取公告，请稍后重试" }
            val limit = 256 * 1024
            check(connection.contentLengthLong <= limit) { "公告内容过大" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= limit) { "公告内容过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val raw = bytes.toString(Charsets.UTF_8)
            val items = parseAnnouncements(raw)
            prefs.edit().putString("feed", raw).putLong("checked_at", now).apply()
            return items
        } finally { connection.disconnect() }
    }
}

internal fun parseAnnouncements(raw: String): List<Announcement> {
    val root = JSONObject(raw)
    require(root.getInt("schemaVersion") == 1) { "暂不支持此公告格式" }
    val array = root.getJSONArray("announcements")
    require(array.length() <= 50) { "公告数量过多" }
    val ids = mutableSetOf<String>()
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id")
        val revision = if (item.has("revision")) item.getInt("revision") else 1
        val title = item.getString("title").trim()
        val body = item.getString("body").trim()
        val publishedAt = Instant.parse(item.getString("publishedAt")).toEpochMilli()
        val expiresAt = if (item.isNull("expiresAt")) null else Instant.parse(item.getString("expiresAt")).toEpochMilli()
        val minVersion = if (item.has("minVersionCode")) item.getLong("minVersionCode") else 1L
        val maxVersion = if (item.isNull("maxVersionCode")) null else item.getLong("maxVersionCode")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && ids.add(id) && revision > 0) { "公告编号无效" }
        require(title.length in 1..100 && body.length in 1..12000) { "公告内容无效" }
        require(expiresAt == null || expiresAt > publishedAt) { "公告有效期无效" }
        require(minVersion > 0 && (maxVersion == null || maxVersion >= minVersion)) { "公告版本范围无效" }
        Announcement(id, revision, title, body, publishedAt, expiresAt, minVersion, maxVersion)
    }.sortedWith(compareByDescending<Announcement> { it.publishedAt }.thenBy { it.id })
}
