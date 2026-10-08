package com.muhan.intelligence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.util.TimeFormatter

/**
 * Conversation history drawer.
 *
 * Grouped by recency (今天 / 昨天 / 7 天内 / 更早) because flat reverse-chronological
 * lists become unnavigable quickly, and search filters live as you type.
 */
@Composable
fun HistoryDrawerContent(
    conversations: List<Conversation>,
    activeConversationId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = scheme.surfaceContainerLow,
        modifier = Modifier.width(312.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------- Header ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuHanAvatar(size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "慕寒智能",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "共 ${conversations.size} 个对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickableNoIndication(onClick = onCloseDrawer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // ---------- New chat ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary)
                    .clickableNoIndication(onClick = onNewChat)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "开启新对话",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---------- Search ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清空",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickableNoIndication { onQueryChange("") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- List ----------
            if (conversations.isEmpty()) {
                EmptyHistory(query.isNotEmpty())
            } else {
                val grouped = remember(conversations) { groupByRecency(conversations) }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    grouped.forEach { (label, items) ->
                        item(key = "header_$label") {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                            )
                        }
                        items(items, key = { it.id }) { conversation ->
                            HistoryRow(
                                conversation = conversation,
                                selected = conversation.id == activeConversationId,
                                onSelect = { onSelect(conversation.id) },
                                onRename = {
                                    renameTarget = conversation
                                    renameDraft = conversation.title
                                },
                                onTogglePin = { onTogglePin(conversation) },
                                onDelete = { deleteTarget = conversation },
                            )
                        }
                    }
                }
            }

            // ---------- Footer ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outlineVariant.copy(alpha = 0.5f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoIndication(onClick = onOpenSettings)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }

    // ---------- Rename dialog ----------
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameDraft.isNotBlank()) onRename(target.id, renameDraft)
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // ---------- Delete dialog ----------
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("「${target.title}」及其全部消息将被永久删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    conversation: Conversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) scheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickableNoIndication(onClick = onSelect)
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            tint = scheme.primary,
                            modifier = Modifier
                                .size(12.dp)
                                .padding(end = 0.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${TimeFormatter.relative(conversation.updatedAt)} · ${conversation.messageCount} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickableNoIndication { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DriveFileRenameOutline,
                    contentDescription = "更多操作",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(18.dp))
                },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp)) },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyHistory(isSearching: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (isSearching) Icons.Outlined.Search else Icons.Outlined.Add,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isSearching) "没有匹配的对话" else "还没有对话记录",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Buckets conversations into human-friendly recency groups. */
private fun groupByRecency(conversations: List<Conversation>): List<Pair<String, List<Conversation>>> {
    val now = System.currentTimeMillis()
    val dayMs = 24 * 60 * 60 * 1000L

    val pinned = conversations.filter { it.pinned }
    val rest = conversations.filterNot { it.pinned }

    val groups = mutableListOf<Pair<String, List<Conversation>>>()
    if (pinned.isNotEmpty()) groups += "已置顶" to pinned

    val today = rest.filter { now - it.updatedAt < dayMs }
    val yesterday = rest.filter { now - it.updatedAt in dayMs until 2 * dayMs }
    val week = rest.filter { now - it.updatedAt in 2 * dayMs until 7 * dayMs }
    val older = rest.filter { now - it.updatedAt >= 7 * dayMs }

    if (today.isNotEmpty()) groups += "今天" to today
    if (yesterday.isNotEmpty()) groups += "昨天" to yesterday
    if (week.isNotEmpty()) groups += "7 天内" to week
    if (older.isNotEmpty()) groups += "更早" to older

    return groups
}
