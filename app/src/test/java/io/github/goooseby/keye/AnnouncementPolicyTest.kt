package io.github.goooseby.keye

import io.github.goooseby.keye.announcements.*
import org.junit.Assert.*
import org.junit.Test

class AnnouncementPolicyTest {
    private val now = 1_800_000_000_000L
    private fun notice(id: String, published: Long = now - 1000, revision: Int = 1,
                       expires: Long? = null, min: Long = 1, max: Long? = null) =
        Announcement(id, revision, "公告", "内容", published, expires, min, max)

    @Test fun futureExpiredAndOtherVersionsNeverPrompt() {
        val items = listOf(notice("future", now + 1), notice("expired", expires = now),
            notice("new-version", min = 10), notice("old-version", max = 8), notice("valid", min = 9, max = 9))
        assertEquals("valid", nextAnnouncement(items, 9, now, { false }, { 0 })?.id)
        assertNull(nextAnnouncement(items.dropLast(1), 9, now, { false }, { 0 }))
    }

    @Test fun newestUnreadNoticeWinsAndReadRevisionStaysDismissed() {
        val older = notice("older", now - 2000)
        val newer = notice("newer")
        assertEquals(newer, nextAnnouncement(listOf(older, newer), 9, now, { false }, { 0 }))
        assertEquals(older, nextAnnouncement(listOf(older, newer), 9, now, { it == newer.key }, { 0 }))
        val revised = newer.copy(revision = 2)
        assertEquals(revised, nextAnnouncement(listOf(revised), 9, now, { it == newer.key }, { 0 }))
    }

    @Test fun snoozeWaitsFullDayAndDoesNotMarkRead() {
        val item = notice("later")
        val until = now + ANNOUNCEMENT_SNOOZE_MILLIS
        assertNull(nextAnnouncement(listOf(item), 9, until - 1, { false }, { until }))
        assertEquals(item, nextAnnouncement(listOf(item), 9, until, { false }, { until }))
        assertNull(nextAnnouncement(listOf(item), 9, until, { true }, { until }))
    }

    @Test fun startupMessagesWaitForHomeAndChecksAndNeverChain() {
        assertNull(chooseAutomaticMessage(false, false, false, true, true))
        assertNull(chooseAutomaticMessage(true, false, true, true, true))
        assertEquals(AutomaticMessage.Update, chooseAutomaticMessage(true, false, false, true, true))
        assertNull(chooseAutomaticMessage(true, true, false, false, true))
        assertEquals(AutomaticMessage.Announcement, chooseAutomaticMessage(true, false, false, false, true))
        assertNull(chooseAutomaticMessage(true, false, false, false, false))
    }

    @Test fun announcementSourceMustBePlainHttpsWithoutCredentialsOrTrackingParameters() {
        assertTrue(validAnnouncementsUrl("https://apps.example.com/keye/announcements.json"))
        listOf("", "http://apps.example.com/feed", "https://user:secret@apps.example.com/feed",
            "https://apps.example.com:8080/feed", "https://apps.example.com/feed?token=secret",
            "https://apps.example.com/feed#fragment", "file:///feed.json").forEach {
            assertFalse(it, validAnnouncementsUrl(it))
        }
    }
}
