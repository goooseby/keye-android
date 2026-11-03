package io.github.goooseby.keye

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate

data class SchoolAuth(
    val cookie: String,
    val jwt: String,
    val userId: String,
    val tenantId: String
)

class SchoolRateLimited(val retryAfterSeconds: Long): IllegalStateException("平台请求频率受限，稍后自动继续")

class SchoolSessionExpired : IllegalStateException("学校登录已失效，请重新登录")

class SchoolApi {
    companion object {
        const val HOME = "https://video.jw.scut.edu.cn/"
        private val tokenPattern = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        fun authFromCookies(cookieHeader: String?): SchoolAuth? {
            if (cookieHeader.isNullOrBlank()) return null
            val values = cookieHeader.split(';').mapNotNull { part ->
                val split = part.indexOf('=')
                if (split <= 0) null else part.substring(0, split).trim() to part.substring(split + 1).trim()
            }.toMap()
            return try {
                val user = JSONObject(URLDecoder.decode(values["JWTUser"] ?: return null, "UTF-8"))
                val jwt = tokenPattern.find(URLDecoder.decode(values["_token"] ?: return null, "UTF-8"))
                    ?.value ?: return null
                val claims = JSONObject(String(Base64.decode(jwt.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
                val expires = claims.optLong("exp", 0)
                if (expires > 0 && expires <= System.currentTimeMillis() / 1000) return null
                val userId = user.optString("id")
                val tenantId = user.optString("tenant_id")
                if (userId.isBlank() || tenantId.isBlank()) null
                else SchoolAuth(cookieHeader, jwt, userId, tenantId)
            } catch (_: Exception) {
                null
            }
        }

        fun ownsEntry(auth: SchoolAuth, entry: ScheduleEntry): Boolean =
            identity("scut", auth.tenantId, auth.userId, entry.courseId, entry.subId) == entry.id

        private fun identity(prefix: String, vararg parts: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(org.json.JSONArray(parts.toList()).toString().toByteArray(Charsets.UTF_8))
            return "$prefix:${bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
        }
    }

    private fun connect(url: String, auth: SchoolAuth?, timeoutSeconds: Int): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "只允许 HTTPS 下载" }
        val connection = parsed.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = timeoutSeconds * 1000
        connection.readTimeout = timeoutSeconds * 1000
        connection.instanceFollowRedirects = false
        connection.useCaches=false
        connection.setRequestProperty("Cache-Control","no-cache")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (parsed.host == "video.jw.scut.edu.cn" && auth != null) {
            connection.setRequestProperty("Referer", HOME)
            connection.setRequestProperty("Origin", "https://video.jw.scut.edu.cn")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Authorization", "Bearer ${auth.jwt}")
            connection.setRequestProperty("Cookie", auth.cookie)
        }
        return connection
    }

    private fun requestBytes(
        url: String, auth: SchoolAuth?, timeoutSeconds: Int,
        followImageRedirects: Boolean = false, redirectCount: Int = 0
    ): ByteArray {
        val connection = connect(url, auth, timeoutSeconds)
        try {
            val status = connection.responseCode
            if (followImageRedirects && status in 300..399) {
                check(redirectCount < 5) { "课件图片重定向次数过多" }
                val location = connection.getHeaderField("Location")
                    ?: error("课件图片重定向地址无效")
                val next = URL(connection.url, location)
                check(next.protocol == "https") { "课件图片只能跳转到 HTTPS 地址" }
                return requestBytes(next.toString(), auth, timeoutSeconds, true, redirectCount + 1)
            }
            if (auth!=null && isSchoolAuthenticationFailure(connection.url.host,status,connection.getHeaderField("Location"),imageRequest=followImageRedirects)) throw SchoolSessionExpired()
            if(status==429) throw SchoolRateLimited(connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(3,300) ?: 30L)
            if (status !in 200..299) {
                throw IllegalStateException("平台请求失败（HTTP $status），请检查登录状态和网络")
            }
            val limit = 40 * 1024 * 1024
            if (connection.contentLengthLong > limit) {
                throw IllegalStateException("课件页面超过 40 MB，已停止下载")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) {
                        throw IllegalStateException("课件页面超过 40 MB，已停止下载")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: Exception) {
            if (error is IllegalStateException) throw error
            throw IllegalStateException("暂时无法连接学校平台，请检查网络后重试", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(
        path: String,
        parameters: Map<String, String>,
        auth: SchoolAuth,
        timeoutSeconds: Int
    ): JSONObject {
        val uri = Uri.parse(HOME.trimEnd('/') + path).buildUpon().apply {
            parameters.forEach { (key, value) -> appendQueryParameter(key, value) }
        }.build()
        val result = requestBytes(uri.toString(), auth, timeoutSeconds)
        val response=try {JSONObject(result.toString(Charsets.UTF_8))}
        catch (_: Exception) {throw IllegalStateException("平台返回了无法解析的数据，请稍后重试")}
        // Only explicit authentication errors invalidate a session. Empty results,
        // server errors and expired CDN image links are not evidence of logout.
        if(response.optString("code") in setOf("401","403") || response.optString("status") in setOf("401","403")) throw SchoolSessionExpired()
        return response
    }

    fun verifySession(auth: SchoolAuth,timeoutSeconds: Int) {
        // One day is sufficient to validate the authenticated schedule endpoint;
        // an empty course list is a successful connection too.
        scan(auth,LocalDate.now(),LocalDate.now(),timeoutSeconds)
    }

    fun scan(auth: SchoolAuth, start: LocalDate, end: LocalDate, timeoutSeconds: Int): List<ScheduleEntry> {
        require(!end.isBefore(start)) { "开始日期不能晚于结束日期" }
        require(java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 220) { "一次最多查询 220 天" }
        val result = mutableListOf<ScheduleEntry>()
        val seen = mutableSetOf<String>()
        var day = start
        while (!day.isAfter(end)) {
            val last = day.plusDays(6).let { if (it.isAfter(end)) end else it }
            val response = getJson(
                "/courseapi/v2/schedule/get-week-schedules",
                mapOf(
                    "user_id" to auth.userId, "tenant_id" to auth.tenantId,
                    "start_at" to day.toString(), "end_at" to last.toString(),
                    "token" to auth.jwt
                ), auth, timeoutSeconds
            )
            val blocks = response.optJSONObject("result")?.optJSONArray("list")
                ?: throw IllegalStateException("课表数据不完整，请稍后重试")
            for (index in 0 until blocks.length()) {
                val block = blocks.getJSONObject(index)
                val date = block.optString("day")
                val courses = block.optJSONArray("course") ?: continue
                for (courseIndex in 0 until courses.length()) {
                    val course = courses.getJSONObject(courseIndex)
                    val subId = course.optString("id")
                    val courseId = course.optString("course_id")
                    if (subId.isBlank() || courseId.isBlank() || !seen.add("$courseId:$subId")) continue
                    result += ScheduleEntry(
                        id = identity("scut", auth.tenantId, auth.userId, courseId, subId),
                        groupId = identity("course", auth.tenantId, auth.userId, courseId),
                        title = course.optString("course_title", "未命名课程"),
                        day = date, courseId = courseId, subId = subId,
                        startsAt = scheduleTimestamp(course.optString("start_at")),
                        endsAt = scheduleTimestamp(course.optString("end_at"))
                    )
                }
            }
            day = last.plusDays(1)
        }
        return result.sortedBy(ScheduleEntry::day)
    }

    fun imageUrls(auth: SchoolAuth, entry: ScheduleEntry, timeoutSeconds: Int): List<String> =
        imagePages(auth,entry,timeoutSeconds).map {it.url}.also {require(it.isNotEmpty()) {"这节课没有可用的课件页面"}}

    fun imagePages(auth: SchoolAuth, entry: ScheduleEntry, timeoutSeconds: Int): List<ClassroomPage> {
        val response = getJson(
            "/pptnote/v1/schedule/search-ppt",
            mapOf(
                "course_id" to entry.courseId, "sub_id" to entry.subId,
                "page" to "1", "per_page" to "1000"
            ), auth, timeoutSeconds
        )
        val items = response.optJSONArray("list")
            ?: throw IllegalStateException("没有收到有效课件列表")
        val urls = linkedSetOf<String>()
        for (index in 0 until items.length()) {
            val raw = items.optJSONObject(index)?.optString("content") ?: continue
            val content = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val url = content.optString("pptimgurl").ifBlank { content.optString("pptthumb") }
            if (url.startsWith("https://")) urls += url
        }
        return urls.map {ClassroomPage(classroomPageKey(it),it)}.distinctBy {it.key}
    }

    fun image(auth: SchoolAuth, url: String, timeoutSeconds: Int): ByteArray =
        requestBytes(url, auth, timeoutSeconds, followImageRedirects = true)
}
