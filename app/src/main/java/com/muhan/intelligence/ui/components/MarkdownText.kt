package com.muhan.intelligence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A pragmatic Markdown renderer for chat output.
 *
 * A full CommonMark engine (or a WebView) would be heavier and slower to stream
 * into, so this handles the subset that actually shows up in model responses:
 * fenced/indented code, headings, lists, blockquotes, rules, plus inline bold,
 * italic, strikethrough and inline code — with per-block copy buttons on code.
 */
object MarkdownRenderer {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class BulletItem(val text: String, val depth: Int, val ordered: Boolean, val index: Int) : Block
        data class Code(val language: String?, val code: String, val complete: Boolean) : Block
        data class Quote(val text: String) : Block
        data object Divider : Block
        data object Spacer : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += Block.Paragraph(paragraph.toString().trim())
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // --- Fenced code -------------------------------------------------
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim().ifBlank { null }
                val body = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) {
                        closed = true
                        i++
                        break
                    }
                    body.appendLine(lines[i])
                    i++
                }
                blocks += Block.Code(
                    language = language,
                    code = body.toString().trimEnd('\n'),
                    // An unterminated fence means the model is still streaming it.
                    complete = closed,
                )
                continue
            }

            // --- Horizontal rule ---------------------------------------------
            if (trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
                flushParagraph()
                blocks += Block.Divider
                i++
                continue
            }

            // --- Heading -----------------------------------------------------
            val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                blocks += Block.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                )
                i++
                continue
            }

            // --- Blockquote ---------------------------------------------------
            if (trimmed.startsWith(">") && !trimmed.startsWith(">>")) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote.appendLine(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks += Block.Quote(quote.toString().trim())
                continue
            }

            // --- Lists --------------------------------------------------------
            val bullet = Regex("^(\\s*)([-*+]|(\\d+)[.)])\\s+(.*)$").find(line)
            if (bullet != null) {
                flushParagraph()
                val indent = bullet.groupValues[1].length
                val ordered = bullet.groupValues[3].isNotEmpty()
                blocks += Block.BulletItem(
                    text = bullet.groupValues[4].trim(),
                    depth = indent / 2,
                    ordered = ordered,
                    index = bullet.groupValues[3].toIntOrNull() ?: 0,
                )
                i++
                continue
            }

            // --- Table --------------------------------------------------------
            if (trimmed.startsWith("|") && i + 1 < lines.size &&
                lines[i + 1].trimStart().startsWith("|") &&
                lines[i + 1].contains("---")
            ) {
                flushParagraph()
                val header = splitTableRow(trimmed)
                i += 2 // skip header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows += splitTableRow(lines[i].trimStart())
                    i++
                }
                blocks += Block.Code(
                    language = "table",
                    code = renderTableAsText(header, rows),
                    complete = true,
                )
                continue
            }

            // --- Blank line ----------------------------------------------------
            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            paragraph.appendLine(line)
            i++
        }

        flushParagraph()
        return blocks
    }

    private fun splitTableRow(row: String): List<String> =
        row.trim().trim('|').split("|").map { it.trim() }

    private fun renderTableAsText(header: List<String>, rows: List<List<String>>): String =
        buildString {
            appendLine(header.joinToString("  |  "))
            appendLine("-".repeat(header.joinToString("  |  ").length.coerceAtMost(60)))
            rows.forEach { appendLine(it.joinToString("  |  ")) }
        }.trimEnd()

    /**
     * Parses inline spans (**bold**, *italic*, ~~strike~~, `code`, [text](url)).
     * Unclosed markers during streaming are rendered literally so text never
     * flickers between plain and styled as tokens arrive.
     */
    fun inline(text: String, linkColor: Color, codeBg: Color, codeFg: Color): AnnotatedString =
        buildAnnotatedString {
            var i = 0
            val sb = StringBuilder()

            fun flush() {
                if (sb.isNotEmpty()) {
                    append(sb.toString())
                    sb.clear()
                }
            }

            while (i < text.length) {
                val rest = text.substring(i)
                when {
                    rest.startsWith("**") && text.indexOf("**", i + 2) > 0 -> {
                        flush()
                        val end = text.indexOf("**", i + 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    }
                    rest.startsWith("__") && text.indexOf("__", i + 2) > 0 -> {
                        flush()
                        val end = text.indexOf("__", i + 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    }
                    rest.startsWith("~~") && text.indexOf("~~", i + 2) > 0 -> {
                        flush()
                        val end = text.indexOf("~~", i + 2)
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    }
                    rest.startsWith("`") && text.indexOf('`', i + 1) > 0 -> {
                        flush()
                        val end = text.indexOf('`', i + 1)
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = codeFg,
                                fontSize = 14.sp,
                            ),
                        ) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    }
                    rest.startsWith("[") && rest.contains("](") -> {
                        val closeBracket = text.indexOf("](", i)
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeBracket > 0 && closeParen > closeBracket) {
                            flush()
                            val label = text.substring(i + 1, closeBracket)
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(label)
                            }
                            i = closeParen + 1
                        } else {
                            sb.append(text[i]); i++
                        }
                    }
                    rest.startsWith("*") && !rest.startsWith("**") &&
                        text.indexOf('*', i + 1) > 0 -> {
                        flush()
                        val end = text.indexOf('*', i + 1)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                    rest.startsWith("_") && !rest.startsWith("__") &&
                        text.indexOf('_', i + 1) > 0 -> {
                        flush()
                        val end = text.indexOf('_', i + 1)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                    else -> {
                        sb.append(text[i])
                        i++
                    }
                }
            }
            flush()
        }
}

/** Renders a parsed Markdown block list. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
) {
    val scheme = MaterialTheme.colorScheme
    val blocks = remember(markdown) { MarkdownRenderer.parse(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownRenderer.Block.Heading -> {
                    Spacer(Modifier.height(if (block.level <= 2) 8.dp else 4.dp))
                    Text(
                        text = MarkdownRenderer.inline(
                            block.text,
                            scheme.primary,
                            scheme.surfaceContainerHighest,
                            scheme.onSurface,
                        ),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        color = scheme.onSurface,
                    )
                }

                is MarkdownRenderer.Block.Paragraph -> {
                    Text(
                        text = MarkdownRenderer.inline(
                            block.text,
                            scheme.primary,
                            scheme.surfaceContainerHighest,
                            scheme.onSurface,
                        ),
                        style = baseStyle,
                        color = scheme.onSurface,
                    )
                }

                is MarkdownRenderer.Block.BulletItem -> {
                    Row(modifier = Modifier.padding(start = (block.depth * 14).dp)) {
                        Text(
                            text = if (block.ordered) "${block.index}." else "•",
                            style = baseStyle,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp),
                        )
                        Text(
                            text = MarkdownRenderer.inline(
                                block.text,
                                scheme.primary,
                                scheme.surfaceContainerHighest,
                                scheme.onSurface,
                            ),
                            style = baseStyle,
                            color = scheme.onSurface,
                        )
                    }
                }

                is MarkdownRenderer.Block.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .heightInMin()
                                .background(scheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = MarkdownRenderer.inline(
                                block.text,
                                scheme.primary,
                                scheme.surfaceContainerHighest,
                                scheme.onSurface,
                            ),
                            style = baseStyle,
                            color = scheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }

                is MarkdownRenderer.Block.Code -> {
                    CodeBlock(
                        code = block.code,
                        language = block.language,
                        streaming = !block.complete,
                    )
                }

                MarkdownRenderer.Block.Divider -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(1.dp)
                            .background(scheme.outlineVariant),
                    )
                }

                MarkdownRenderer.Block.Spacer -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun Modifier.heightInMin(): Modifier = this

/** Fenced code block with a language chip and a copy-to-clipboard action. */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerHighest.copy(alpha = if (scheme.surface == Color.Black) 0.6f else 0.5f))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.uppercase() ?: if (streaming) "生成中…" else "代码",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .padding(2.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.clickableNoIndication {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                            scope.launch {
                                delay(1600)
                                copied = false
                            }
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                color = scheme.onSurface,
            )
        }
    }
}
