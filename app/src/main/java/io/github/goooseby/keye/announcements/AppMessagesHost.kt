package io.github.goooseby.keye.announcements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.SheetHeading
import io.github.goooseby.keye.update.AppUpdateHost
import io.github.goooseby.keye.update.AppUpdateState
import io.github.goooseby.keye.update.AppUpdateViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AppMessagesHost(updates: AppUpdateViewModel, notices: AnnouncementViewModel, canPrompt: Boolean) {
    val update by updates.state.collectAsState()
    val notice by notices.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var used by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val noticeOpen = notice.boardOpen || notice.selected != null
    val updateOpen = update.panelOpen || update.preparingInstall || update.installer != null || update.autoInstallPending

    LaunchedEffect(owner, canPrompt) {
        if (canPrompt) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { notices.refresh() }
    }
    LaunchedEffect(canPrompt, resumed, used, update.initializing, update.checking, notice.checking, update.prompt, notice.pending, noticeOpen, updateOpen) {
        when (chooseAutomaticMessage(canPrompt && resumed && !noticeOpen && !updateOpen, used,
            update.initializing || update.checking || notice.checking, update.prompt, notice.pending?.active(System.currentTimeMillis()) == true)) {
            AutomaticMessage.Update -> { used = true; showUpdate = true }
            AutomaticMessage.Announcement -> { used = true; notice.pending?.let(notices::open) }
            null -> Unit
        }
    }
    AppUpdateHost(updates, canPrompt = canPrompt && !noticeOpen, automaticPromptAllowed = showUpdate)
    // Explicit update actions take precedence. Keep the notice ready to return to.
    if (resumed && !updateOpen) AnnouncementHost(notices, update, updates::showDetails, { updates.check() })
}

@Composable
internal fun MessageInboxButton(unreadCount: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (unreadCount > 0) UnreadMessageDot() }) {
            AppIcon("mail", if (unreadCount > 0) "消息，${unreadCount} 条未读" else "消息")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementHost(model: AnnouncementViewModel, update: AppUpdateState, onUpdate: () -> Unit, refreshUpdates: () -> Unit) {
    val state by model.state.collectAsState()
    val selected = state.selected
    if (selected != null) {
        val read = selected.key in state.readKeys
        AlertDialog(
            onDismissRequest = model::later,
            icon = { AppIcon("info") },
            title = { Text(selected.title) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("应用公告 · ${announcementDate(selected.publishedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected.body, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = model::markRead) { Text(if (read) "关闭" else "已阅读") } },
            dismissButton = { if (!read) TextButton(onClick = model::later) { Text("稍后提醒") } }
        )
    } else if (state.boardOpen) {
        val unreadCount = state.unreadCount + if (update.unreadRelease) 1 else 0
        val checking = state.checking || update.checking || update.initializing
        ModalBottomSheet(onDismissRequest = model::closeBoard, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetHeading("消息", model::closeBoard)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (unreadCount > 0) Text("${unreadCount} 条未读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { model.refresh(manual = true); refreshUpdates() }, enabled = !checking) { Text(if (checking) "刷新中" else "刷新") }
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.items.isEmpty() && update.release == null) {
                    Text(if (checking) "正在获取消息…" else "暂无消息", Modifier.padding(vertical = 32.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    update.release?.let { release ->
                        item(key = "application-update") {
                            MessageRow("新版本 ${release.version}", "应用更新", update.unreadRelease, onUpdate)
                        }
                    }
                    items(state.items, key = { it.key }) { item ->
                        MessageRow(item.title, announcementDate(item.publishedAt), item.key !in state.readKeys) { model.openFromBoard(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(title: String, subtitle: String, unread: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (unread) "未读" else "已读" }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (unread) UnreadMessageDot()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun UnreadMessageDot() {
    Badge(containerColor = MaterialTheme.colorScheme.error)
}

private fun announcementDate(millis: Long): String = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(millis))
