package com.muhan.intelligence.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.muhan.intelligence.domain.model.AttachmentKind
import com.muhan.intelligence.domain.model.MessageAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Base64
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Materialises picked content Uris into the app's private storage so attachments
 * survive restarts, and converts them to wire-ready payloads (base64 data URLs
 * for images, plain text for text files).
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    /** Image mime types the major vision APIs accept directly. */
    private val imageMimes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /**
     * Copies [uri] into private storage. Returns null when the content cannot be
     * read (revoked permission, missing file); the caller just skips it.
     */
    suspend fun import(uri: Uri): MessageAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(resolver, uri) ?: "attachment"
            val kind = if (mime in imageMimes) AttachmentKind.IMAGE else AttachmentKind.FILE

            val safeName = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]+"), "_")
            val target = File(dir, "${System.nanoTime()}_$safeName")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null

            MessageAttachment(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                mimeType = mime,
                kind = kind,
                sizeBytes = target.length(),
                localPath = target.absolutePath,
            )
        }.getOrNull()
    }

    /** Reads an image attachment as a `data:` URL suitable for vision APIs. */
    fun imageDataUrl(attachment: MessageAttachment): String? {
        if (!attachment.isImage) return null
        val file = File(attachment.localPath)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:${attachment.mimeType};base64,$b64"
        }.getOrNull()
    }

    /** Reads a text file attachment, capped to keep prompts reasonable. */
    fun readText(attachment: MessageAttachment, maxChars: Int = 20_000): String? {
        if (attachment.isImage) return null
        val file = File(attachment.localPath)
        if (!file.exists()) return null
        val looksTextual = attachment.mimeType.startsWith("text/") ||
            attachment.mimeType.contains("json") ||
            attachment.mimeType.contains("xml") ||
            attachment.mimeType.contains("csv") ||
            attachment.mimeType.contains("javascript") ||
            attachment.name.endsWith(".md") ||
            attachment.name.endsWith(".txt") ||
            attachment.name.endsWith(".kt") ||
            attachment.name.endsWith(".py") ||
            attachment.name.endsWith(".java") ||
            attachment.name.endsWith(".log")
        if (!looksTextual) return null
        return runCatching { file.readText().take(maxChars) }.getOrNull()
    }

    /** Writes a base64 image body to private storage and returns the file path. */
    fun saveBase64Image(base64: String): String? = runCatching {
        val target = File(dir, "generated_${System.nanoTime()}.png")
        target.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        target.absolutePath
    }.getOrNull()

    fun serialize(attachments: List<MessageAttachment>): String =
        if (attachments.isEmpty()) "[]" else json.encodeToString(attachments)

    fun deserialize(raw: String?): List<MessageAttachment> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<MessageAttachment>>(raw) }.getOrDefault(emptyList())
        }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
}
