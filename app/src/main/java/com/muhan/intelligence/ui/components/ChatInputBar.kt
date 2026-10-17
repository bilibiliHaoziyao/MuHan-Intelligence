package com.muhan.intelligence.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muhan.intelligence.domain.model.MessageAttachment

/**
 * The composer.
 *
 * Behaves like a modern chat input: grows with content up to a cap, Enter inserts
 * a newline (mobile convention — sending is the explicit button), and the send
 * button morphs into a stop button while generating. A capability strip above the
 * field toggles deep thinking / web search / image mode, and the "+" button opens
 * the attachment pickers.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    imageMode: Boolean = false,
    onToggleImageMode: () -> Unit = {},
    imageModeAvailable: Boolean = false,
    onMicPress: () -> Unit = {},
    onMicRelease: () -> Unit = {},
    onMicCancel: () -> Unit = {},
    isRecording: Boolean = false,
    recordingSeconds: Int = 0,
    voiceHint: String? = null,
    micEnabled: Boolean = true,
    pendingAttachments: List<MessageAttachment> = emptyList(),
    onAddAttachment: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    val borderColor by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        label = "inputBorder",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 能力条：1.0.0 起移除深度思考 / 联网搜索 / 附件 / 文件入口，仅保留生图（按服务商能力出现）。
            if (imageModeAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapabilityChip(
                        icon = { Icon(Icons.Outlined.Image, null, Modifier.size(13.dp)) },
                        label = "生图",
                        active = imageMode,
                        enabled = enabled && !isGenerating,
                        onClick = onToggleImageMode,
                    )
                }
            }

            if (pendingAttachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingAttachments.forEach { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment.id) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (imageMode) scheme.tertiaryContainer.copy(alpha = 0.45f) else scheme.surfaceContainerHigh,
                    )
                    .border(
                        width = 1.4.dp,
                        color = if (imageMode) {
                            scheme.tertiary.copy(alpha = 0.5f)
                        } else {
                            scheme.primary.copy(alpha = 0.15f + 0.55f * borderColor)
                        },
                        shape = RoundedCornerShape(24.dp),
                    )
                    .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .clickableNoIndication(enabled = enabled, onClick = onAddAttachment),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加附件",
                        tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(21.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 168.dp)
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = if (imageMode) "描述画面…" else hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused },
                    )
                }

                Spacer(Modifier.width(6.dp))

                MicButton(
                    enabled = micEnabled,
                    isRecording = isRecording,
                    onPress = onMicPress,
                    onRelease = onMicRelease,
                    onCancel = onMicCancel,
                )

                Spacer(Modifier.width(6.dp))

                SendOrStopButton(
                    isGenerating = isGenerating,
                    canSend = enabled && value.isNotBlank(),
                    onSend = {
                        focusManager.clearFocus()
                        onSend()
                    },
                    onStop = onStop,
                    imageMode = imageMode,
                )
            }

            if (isRecording) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "录音 ${recordingSeconds}s · 松开发送",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                    )
                }
            } else if (voiceHint != null) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = voiceHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = isGenerating, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (imageMode) "正在生成图片…" else "回复中，点停止可中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    icon: @Composable () -> Unit,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        active -> scheme.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        active -> scheme.onPrimaryContainer
        enabled -> scheme.onSurfaceVariant
        else -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickableNoIndication(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChip(attachment: MessageAttachment, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = attachment.name.take(18),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .clickableNoIndication(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除附件",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun MicButton(
    enabled: Boolean,
    isRecording: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(
                when {
                    isRecording -> scheme.error.copy(alpha = 0.18f)
                    else -> scheme.surfaceContainerHighest
                },
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        val released = tryAwaitRelease()
                        if (released) onRelease() else onCancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = if (isRecording) "松开发送" else "按住说话",
            tint = when {
                isRecording -> scheme.error
                enabled -> scheme.onSurfaceVariant
                else -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SendOrStopButton(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    imageMode: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val isActive = isGenerating || canSend

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(
                when {
                    isGenerating -> scheme.surfaceContainerHighest
                    canSend -> if (imageMode) scheme.tertiary else scheme.primary
                    else -> scheme.surfaceContainerHighest
                },
            )
            .clickableNoIndication(enabled = isActive) {
                if (isGenerating) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isGenerating) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = "停止生成",
                tint = scheme.onSurface,
                modifier = Modifier.size(19.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "发送",
                tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Rotating-hint tile shown on the empty chat state to seed a first prompt. */
@Composable
fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickableNoIndication(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Animated app mark used on the empty state. */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp = 64.dp, animated: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (animated) 1f else 0.96f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 180f),
        label = "brandScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(size / 3f))
            .background(scheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(com.muhan.intelligence.R.drawable.ic_brand_logo),
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
