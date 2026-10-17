package com.muhan.intelligence.ui.screens.agent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.domain.model.AgentStep
import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.domain.model.MODE_AGENT
import com.muhan.intelligence.domain.model.MODE_CHAT
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.components.ChatInputBar
import com.muhan.intelligence.ui.components.HistoryDrawerContent
import com.muhan.intelligence.ui.components.MarkdownText
import com.muhan.intelligence.ui.components.ModeSwitcher
import com.muhan.intelligence.ui.components.MuHanAvatar
import com.muhan.intelligence.ui.components.ReasoningPanel
import com.muhan.intelligence.ui.components.SuggestionChip
import com.muhan.intelligence.ui.components.clickableNoIndication
import kotlinx.coroutines.launch

/**
 * Agent 模式界面（3.0.0 Dev）。
 *
 * 与 Chat 平级、会话独立；顶部「对话 / Agent」切换，默认进入对话。
 */
@Composable
fun AgentScreen(
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onSwitchToChat: () -> Unit,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startRecording() }
    val micGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris) }

    var searchQuery by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<Conversation>()) }
    LaunchedEffect(searchQuery) {
        history = viewModel.observeHistory(searchQuery)
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
            AgentTopBar(
                subtitle = state.activeProvider?.let { "${it.displayName} · ${it.displayModelLabel}" },
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = onNewChat,
                onSwitchToChat = onSwitchToChat,
            )

            AnimatedVisibility(
                visible = state.errorBanner != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                AgentBanner(
                    message = state.errorBanner.orEmpty(),
                    onDismiss = viewModel::dismissBanner,
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    state.isEmptyConversation && !state.isRunning -> AgentEmptyState(
                        onSuggestion = viewModel::onInputChange,
                    )

                    else -> AgentMessageList(
                        messages = state.messages,
                        isRunning = state.isRunning,
                        liveContent = state.liveContent,
                        liveReasoning = state.liveReasoning,
                        liveSteps = state.liveSteps,
                        speakingMessageId = state.speakingMessageId,
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        onSpeak = viewModel::speakMessage,
                    )
                }
            }

            ChatInputBar(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                isGenerating = state.isRunning,
                enabled = true,
                hint = if (state.hasProvider) "告诉 Agent 你想完成什么…" else "请先在设置中配置模型服务",
                onMicPress = {
                    if (micGranted) viewModel.startRecording()
                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onMicRelease = viewModel::stopRecordingAndTranscribe,
                onMicCancel = viewModel::cancelRecording,
                isRecording = state.isRecording,
                recordingSeconds = state.recordingSeconds,
                voiceHint = state.voiceHint,
                micEnabled = state.hasProvider,
                pendingAttachments = state.pendingAttachments,
                onAddAttachment = {
                    attachLauncher.launch(arrayOf("image/*", "text/*", "application/json"))
                },
                onRemoveAttachment = viewModel::removeAttachment,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun AgentTopBar(
    subtitle: String?,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onSwitchToChat: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickableNoIndication(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Menu, "对话历史", tint = scheme.onSurface, modifier = Modifier.size(21.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSwitcher(
                current = MODE_AGENT,
                onSelect = { if (it == MODE_CHAT) onSwitchToChat() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickableNoIndication(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, "新会话", tint = scheme.onSurface, modifier = Modifier.size(21.dp))
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
private fun AgentBanner(message: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.errorContainer)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = scheme.onErrorContainer, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.Close,
            "关闭",
            tint = scheme.onErrorContainer,
            modifier = Modifier
                .size(18.dp)
                .clickableNoIndication(onClick = onDismiss),
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun AgentMessageList(
    messages: List<ChatMessage>,
    isRunning: Boolean,
    liveContent: String,
    liveReasoning: String,
    liveSteps: List<AgentStep>,
    speakingMessageId: String?,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val lastIndex = messages.size
    LaunchedEffect(messages.size, liveContent.length, liveReasoning.length, liveSteps.size) {
        val target = if (isRunning) lastIndex else messages.lastIndex
        if (target >= 0) listState.animateScrollToItem(target)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(items = messages.distinctBy { it.id }, key = { it.id }) { message ->
            AgentMessageBubble(
                message = message,
                isSpeaking = message.id == speakingMessageId,
                onCopy = { onCopy(message.content) },
                onSpeak = { onSpeak(message.id) },
            )
        }
        if (isRunning) {
            item(key = "agent_live") {
                AgentLiveBubble(
                    content = liveContent,
                    reasoning = liveReasoning,
                    steps = liveSteps,
                )
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (message.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = scheme.primary,
                contentColor = scheme.onPrimary,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    message.attachments.forEach { attachment ->
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                contentDescription = null,
                                tint = scheme.onPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MuHanAvatar(size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.modelName ?: "慕寒智能",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (message.content.isNotBlank()) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp,
                    contentDescription = if (isSpeaking) "停止朗读" else "朗读",
                    tint = if (isSpeaking) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickableNoIndication(onClick = onSpeak)
                        .padding(6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (message.hasReasoning) {
            ReasoningPanel(
                reasoning = message.reasoningContent,
                streaming = false,
                defaultExpanded = false,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (message.hasAgentSteps) {
            AgentStepsPanel(steps = message.agentSteps, running = false)
            Spacer(Modifier.height(8.dp))
        }

        if (message.status == MessageStatus.FAILED) {
            Text(
                text = message.errorMessage ?: "任务执行中断",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        } else {
            MarkdownText(
                markdown = message.content,
                baseStyle = MaterialTheme.typography.bodyLarge,
            )
        }

        if (message.content.isNotBlank()) {
            Row(Modifier.padding(top = 2.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoIndication(onClick = onCopy)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentLiveBubble(content: String, reasoning: String, steps: List<AgentStep>) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MuHanAvatar(size = 26.dp, animated = true)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent 执行中",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (reasoning.isNotBlank()) {
            ReasoningPanel(
                reasoning = reasoning,
                streaming = content.isBlank() && steps.isEmpty(),
                defaultExpanded = true,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (steps.isNotEmpty()) {
            AgentStepsPanel(steps = steps, running = true)
            Spacer(Modifier.height(8.dp))
        }
        if (content.isNotBlank()) {
            MarkdownText(
                markdown = content,
                baseStyle = MaterialTheme.typography.bodyLarge,
            )
        } else if (steps.isEmpty() && reasoning.isBlank()) {
            Text(
                text = "正在思考…",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentStepsPanel(steps: List<AgentStep>, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(running) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                null,
                tint = scheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (running) "工具调用 · ${steps.size} 步（执行中）" else "工具调用 · ${steps.size} 步",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp)) {
                steps.forEach { step ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (step.ok) scheme.primary else scheme.error),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = toolLabel(step.name),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurface,
                            )
                            if (step.arguments.isNotBlank() && step.arguments != "{}") {
                                Text(
                                    text = step.arguments.take(140),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (step.result.isNotBlank()) {
                                Text(
                                    text = step.result.take(200),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toolLabel(name: String): String = when (name) {
    "get_time" -> "获取时间"
    "calculate" -> "计算"
    "fetch_url" -> "读取网页"
    "save_note" -> "保存笔记"
    "read_note" -> "读取笔记"
    "list_notes" -> "列出笔记"
    "web_search" -> "联网搜索"
    else -> name
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AgentEmptyState(onSuggestion: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val suggestions = remember {
        listOf(
            "搜索今天的科技新闻并总结",
            "把这段会议记录整理成待办清单",
            "查一下北京明天的天气",
            "帮我算一下 12800 的 15% 是多少",
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
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Agent 模式",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "我会自主调用工具（联网搜索、网页读取、计算、笔记）完成任务",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            suggestions.forEach { s ->
                SuggestionChip(text = s, onClick = { onSuggestion(s) })
            }
        }
    }
}
