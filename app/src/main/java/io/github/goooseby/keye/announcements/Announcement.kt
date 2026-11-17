package io.github.goooseby.keye.announcements

import java.net.URI

internal const val ANNOUNCEMENT_SNOOZE_MILLIS = 24 * 60 * 60 * 1000L

internal data class Announcement(
    val id: String,
    val revision: Int,
    val title: String,
    val body: String,
    val publishedAt: Long,
    val expiresAt: Long? = null,
    val minVersionCode: Long = 1,
    val maxVersionCode: Long? = null
) {
    val key get() = "$id:$revision"
    fun appliesTo(versionCode: Long) = versionCode >= minVersionCode &&
        (maxVersionCode == null || versionCode <= maxVersionCode)
    fun active(now: Long) = publishedAt <= now && (expiresAt == null || now < expiresAt)
}

internal fun nextAnnouncement(
    items: List<Announcement>, versionCode: Long, now: Long,
    isRead: (String) -> Boolean, snoozedUntil: (String) -> Long
): Announcement? = items.asSequence()
    .filter { it.appliesTo(versionCode) && it.active(now) && !isRead(it.key) && snoozedUntil(it.key) <= now }
    .sortedWith(compareByDescending<Announcement> { it.publishedAt }.thenBy { it.id })
    .firstOrNull()

internal fun validAnnouncementsUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawFragment == null && uri.rawQuery == null && (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)

internal enum class AutomaticMessage { Update, Announcement }

/** One automatic message per visit; explicit views remain available afterwards. */
internal fun chooseAutomaticMessage(
    allowed: Boolean, alreadyShown: Boolean, checking: Boolean,
    hasUpdate: Boolean, hasAnnouncement: Boolean
): AutomaticMessage? = when {
    !allowed || alreadyShown || checking -> null
    hasUpdate -> AutomaticMessage.Update
    hasAnnouncement -> AutomaticMessage.Announcement
    else -> null
}
