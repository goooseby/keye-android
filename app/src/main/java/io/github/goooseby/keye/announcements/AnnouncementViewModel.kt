package io.github.goooseby.keye.announcements

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AnnouncementState(
    val items: List<Announcement> = emptyList(), val readKeys: Set<String> = emptySet(),
    val pending: Announcement? = null, val selected: Announcement? = null,
    val boardOpen: Boolean = false, val checking: Boolean = false,
    val message: String? = null
) {
    val unreadCount get() = items.count { it.key !in readKeys }
}

internal class AnnouncementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnnouncementRepository(application)
    private val versionCode = application.packageManager.getPackageInfo(application.packageName, 0).longVersionCodeCompat()
    private val mutable = MutableStateFlow(AnnouncementState())
    val state = mutable.asStateFlow()

    init { refresh() }

    private fun publish(items: List<Announcement>) {
        val now = System.currentTimeMillis()
        // Expired notices remain in history; scheduled and unrelated notices stay hidden.
        val visible = items.filter { it.appliesTo(versionCode) && it.publishedAt <= now }
        mutable.update { it.copy(items = visible, readKeys = visible.filter { notice -> repository.isRead(notice.key) }.map { notice -> notice.key }.toSet(),
            pending = nextAnnouncement(items, versionCode, now, repository::isRead, repository::snoozedUntil)) }
    }

    fun refresh(manual: Boolean = false) {
        if (state.value.checking) return
        mutable.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { repository.cached() }
                publish(cached)
                val fresh = withContext(Dispatchers.IO) { repository.refresh(manual, System.currentTimeMillis()) }
                publish(fresh)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (manual) mutable.update { it.copy(message = "暂时无法获取公告，请稍后重试") }
            } finally { mutable.update { it.copy(checking = false) } }
        }
    }

    fun showBoard() { mutable.update { it.copy(boardOpen = true, message = null) }; refresh() }
    fun closeBoard() { mutable.update { it.copy(boardOpen = false) } }
    fun open(item: Announcement) { mutable.update { it.copy(selected = item) } }
    fun openFromBoard(item: Announcement) {
        repository.markRead(item.key)
        open(item)
        publish(state.value.items)
    }
    fun markRead() {
        state.value.selected?.let { repository.markRead(it.key) }
        mutable.update { it.copy(selected = null) }
        publish(state.value.items)
    }
    fun later() {
        state.value.selected?.let { if (!repository.isRead(it.key)) repository.snooze(it.key, System.currentTimeMillis()) }
        mutable.update { it.copy(selected = null) }
        publish(state.value.items)
    }
}

@Suppress("DEPRECATION")
private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
