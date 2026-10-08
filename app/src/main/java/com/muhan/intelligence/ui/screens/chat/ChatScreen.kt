package com.muhan.intelligence.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.components.ChatInputBar
import com.muhan.intelligence.ui.components.HistoryDrawerContent
import com.muhan.intelligence.ui.components.MessageBubble
import com.muhan.intelligence.ui.components.SuggestionChip
import com.muhan.intelligence.ui.components.clickableNoIndication
import kotlinx.coroutines.launch

/**
 * The primary chat surface.
 *
 * Scroll behaviour: auto-follows the tail while streaming, but yields control the
 * moment the user scrolls up — nothing is more annoying than being yanked back
 * down mid-read.
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<com.muhan.intelligence.domain.model.Conversation>()) }

    // Drawer history: separate flow so search can re-query without touching chat state.
    val historyViewModel = viewModel
    LaunchedEffect(isSearching, searchQuery) {
        // Delegated through the view model to keep DAO access out of the UI layer.
        history = historyViewModel.observeHistory(searchQuery)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawerContent(
                conversations = history,
                activeConversationId = state.conversationId,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSelect = { id ->
                    scope.launch { drawerState.close() }
                    onSelectConversation(id)
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    onNewChat()
                },
                onRename = { id, title -> viewModel.renameConversation(id, title) },
                onTogglePin = { viewModel.togglePinned(it.id, !it.pinned) },
                onDelete = { id ->
                    viewModel.deleteConversation(id)
                    if (id == state.conversationId) onNewChat()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onCloseDrawer = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding(),
        ) {
            ChatTopBar(
                title = state.conversation?.title ?: "新对话",
                subtitle = state.activeProvider?.let { "${it.displayName} · ${it.modelName}" },
                isGenerating = state.isGenerating,
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = onNewChat,
                onOpenSettings = onOpenSettings,
            )

            AnimatedVisibility(
                visible = state.errorBanner != null || state.infoBanner != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BannerStrip(
                    message = state.errorBanner ?: state.infoBanner.orEmpty(),
                    isError = state.errorBanner != null,
                    onDismiss = viewModel::dismissBanners,
                    onAction = state.activeProvider?.let { null } ?: "去配置" to onOpenSettings,
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading -> LoadingState()
                    state.isEmptyConversation -> EmptyChatState(
                        hasProvider = state.hasProvider,
                        onSuggestion = { text ->
                            viewModel.onInputChange(text)
                        },
                        onConfigure = onOpenSettings,
                    )
                    else -> MessageList(
                        messages = state.messages,
                        isGenerating = state.isGenerating,
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        onRetry = viewModel::retryMessage,
                        onEdit = viewModel::editAndResend,
                    )
                }
            }

            ChatInputBar(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stopGenerating,
                isGenerating = state.isGenerating,
                enabled = true,
                hint = if (state.hasProvider) "给慕寒智能发送消息…" else "请先在设置中配置模型服务",
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String?,
    isGenerating: Boolean,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonBox(onClick = onMenu, contentDescription = "对话历史") {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(9.dp),
                            strokeWidth = 1.5.dp,
                            color = scheme.primary,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        IconButtonBox(onClick = onNewChat, contentDescription = "新对话") {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(scheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

@Composable
private fun IconButtonBox(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickableNoIndication(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BannerStrip(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    onAction: Pair<String, () -> Unit>?,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (isError) scheme.errorContainer else scheme.secondaryContainer
    val fg = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isError) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        onAction?.let { (label, action) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableNoIndication(onClick = action)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "关闭",
            tint = fg,
            modifier = Modifier
                .size(18.dp)
                .clickableNoIndication(onClick = onDismiss),
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onCopy: (String) -> Unit,
    onRetry: (String) -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()

    // Track whether we should keep pinning to the bottom.
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }.collect { atBottom -> autoFollow = atBottom }
    }

    val lastIndex = messages.lastIndex
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (autoFollow && lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(items = messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                isLastAssistant = message.id == messages.lastOrNull()?.id,
                onCopy = { onCopy(message.content) },
                onRetry = { onRetry(message.id) },
                onEdit = { newText -> onEdit(message.id, newText) },
            )
        }

        if (isGenerating) {
            item(key = "bottom_spacer") { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatState(
    hasProvider: Boolean,
    onSuggestion: (String) -> Unit,
    onConfigure: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val suggestions = remember {
        listOf(
            "用简单的比喻解释量子纠缠",
            "帮我写一个 Python 快速排序",
            "把这段话润色得更专业",
            "制定一份四周健身计划",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "你好，我是慕寒智能",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasProvider) {
                "随时向我提问，我会尽力给出清晰可靠的回答"
            } else {
                "还没有配置模型服务，先完成设置即可开始对话"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        if (hasProvider) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        text = suggestion,
                        onClick = { onSuggestion(suggestion) },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary)
                    .clickableNoIndication(onClick = onConfigure)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "前往配置模型服务",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                )
            }
        }
    }
}
