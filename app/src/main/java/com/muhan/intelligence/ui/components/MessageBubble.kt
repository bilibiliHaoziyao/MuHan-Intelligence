package com.muhan.intelligence.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders one turn.
 *
 * Layout choice: user turns are right-aligned in a tinted bubble (familiar chat
 * idiom), assistant turns are full-width flat text. That asymmetry keeps long
 * Markdown output readable edge-to-edge instead of squeezed into a bubble.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.USER -> UserMessageBubble(
            message = message,
            onCopy = onCopy,
            onEdit = onEdit,
            modifier = modifier,
        )
        Role.ASSISTANT, Role.SYSTEM -> AssistantMessageBubble(
            message = message,
            isLastAssistant = isLastAssistant,
            onCopy = onCopy,
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf(message.content) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            if (editing) {
                EditableUserBubble(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onCancel = { editing = false; draft = message.content },
                    onConfirm = {
                        editing = false
                        onEdit(draft)
                    },
                )
            } else {
                Surface(
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        // Attached photos render above the caption inside the bubble.
                        message.attachments.filter { it.isImage }.forEach { attachment ->
                            coil.compose.AsyncImage(
                                model = java.io.File(attachment.localPath),
                                contentDescription = attachment.name,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            )
                        }
                        if (message.content.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }

                // Non-image attachments appear as chips under the bubble.
                val fileAttachments = message.attachments.filterNot { it.isImage }
                if (fileAttachments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    fileAttachments.forEach { attachment ->
                        Surface(
                            color = scheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = attachment.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }

            if (!editing) {
                MessageActions(
                    actions = listOf(
                        MessageAction(
                            icon = Icons.Outlined.ContentCopy,
                            label = "复制",
                            onClick = onCopy,
                        ),
                        MessageAction(
                            icon = Icons.Outlined.Edit,
                            label = "编辑",
                            onClick = { editing = true },
                        ),
                    ),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EditableUserBubble(
    draft: String,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
        modifier = Modifier.widthIn(min = 220.dp, max = 340.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 2,
                maxLines = 8,
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
                androidx.compose.material3.Button(
                    onClick = onConfirm,
                    enabled = draft.isNotBlank() && draft != "",
                ) { Text("重新发送") }
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    message: ChatMessage,
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val isStreaming = message.status == MessageStatus.STREAMING
    val isFailed = message.status == MessageStatus.FAILED
    val showEmptyThinking = isStreaming && message.content.isBlank() && !message.hasReasoning

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        // Brand row: small avatar + model name, so users can tell local vs cloud.
        Row(verticalAlignment = Alignment.CenterVertically) {
            MuHanAvatar(size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.modelName ?: "慕寒智能",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            if (isStreaming && message.hasReasoning && message.content.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考中",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (message.hasReasoning) {
            ReasoningPanel(
                reasoning = message.reasoningContent,
                streaming = isStreaming && message.content.isEmpty(),
                defaultExpanded = isStreaming && message.content.isEmpty(),
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            isFailed -> {
                ErrorCard(
                    message = message.errorMessage ?: "生成失败",
                    onRetry = onRetry,
                )
            }

            message.isImageMessage -> {
                // Image-model reply: the picture is the answer, caption goes below.
                GeneratedImageViewer(source = message.imageUrl.orEmpty())
                if (message.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.content,
                            baseStyle = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            showEmptyThinking -> {
                ThinkingIndicator()
            }

            else -> {
                SelectionContainer {
                    MarkdownText(
                        markdown = message.content,
                        baseStyle = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    StreamingCaret()
                }
            }
        }

        if (!isStreaming && isLastAssistant && !isFailed && message.content.isNotBlank()) {
            MessageActions(
                actions = listOf(
                    MessageAction(
                        icon = Icons.Outlined.ContentCopy,
                        label = "复制",
                        onClick = onCopy,
                    ),
                    MessageAction(
                        icon = Icons.Outlined.Refresh,
                        label = "重新生成",
                        onClick = onRetry,
                    ),
                ),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Displays a generated image, from either a local file path or a remote URL. */
@Composable
private fun GeneratedImageViewer(source: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        coil.compose.AsyncImage(
            model = if (source.startsWith("http")) source else java.io.File(source),
            contentDescription = "生成的图片",
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(6.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

private data class MessageAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun MessageActions(
    actions: List<MessageAction>,
    tint: androidx.compose.ui.graphics.Color,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var flashIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(flashIndex) {
        if (flashIndex >= 0) {
            delay(1400)
            flashIndex = -1
        }
    }

    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            val flashed = flashIndex == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableNoIndication {
                        if (action.label == "复制") {
                            flashIndex = index
                        }
                        action.onClick()
                    }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = if (flashed) Icons.Outlined.Check else action.icon,
                    contentDescription = action.label,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (flashed) "已复制" else action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}

/** Collapsible chain-of-thought panel for reasoning models. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReasoningPanel(
    reasoning: String,
    streaming: Boolean,
    defaultExpanded: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(defaultExpanded) }
    var manuallyToggled by remember { mutableStateOf(false) }

    // Auto-collapse the moment the real answer starts arriving, unless the user
    // explicitly opened it — their intent should win over our heuristic.
    LaunchedEffect(streaming) {
        if (!streaming && !manuallyToggled) expanded = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    com.muhan.intelligence.ui.theme.MuHanColors.reasoningDark
                } else {
                    com.muhan.intelligence.ui.theme.MuHanColors.reasoningLight
                },
            )
            .border(
                1.dp,
                scheme.outlineVariant.copy(alpha = 0.45f),
                MaterialTheme.shapes.small,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoIndication {
                    expanded = !expanded
                    manuallyToggled = true
                }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (streaming) "正在思考…" else "深度思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = reasoning.trim(),
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 22.sp,
                    color = scheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/** Three-dot pulse shown before the first token lands. */
@Composable
fun ThinkingIndicator() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = alpha)),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "正在生成回复",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Blinking cursor appended while text streams in. */
@Composable
private fun StreamingCaret() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .size(width = 8.dp, height = 16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(scheme.primary.copy(alpha = alpha)),
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.errorContainer.copy(alpha = 0.35f))
            .border(1.dp, scheme.error.copy(alpha = 0.35f), MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "这次没有成功",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.error,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickableNoIndication(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.error,
            )
        }
    }
}

/** Circular brand avatar used in message headers and headers. */
@Composable
fun MuHanAvatar(size: androidx.compose.ui.unit.Dp = 28.dp, animated: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "avatar")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "avatarRotation",
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2.6f))
            .background(
                Brush.linearGradient(
                    listOf(scheme.primary, scheme.tertiary),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(com.muhan.intelligence.R.drawable.ic_brand_logo),
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier
                .size(size * 0.62f)
                .rotate(rotation),
        )
    }
}

/** Small inline spinner row used by long-running actions. */
@Composable
fun InlineLoading(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A section label used across settings screens. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
