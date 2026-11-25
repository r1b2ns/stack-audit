package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.Category
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal enum class Tone { Success, Redirect, ClientError, ServerError, Failed, Neutral }

internal object Presentation {
    private val pretty = Json { prettyPrint = true }

    val filters: List<Category?> = listOf(null, Category.Http, Category.WebSocket, Category.Storage, Category.Database, Category.Audit)

    fun filterLabel(category: Category?): String =
        when (category) {
            null -> "All"
            Category.Http -> "Network"
            Category.WebSocket -> "WebSocket"
            Category.Storage -> "Storage"
            Category.Database -> "Database"
            Category.Analytics -> "Analytics"
            Category.Flag -> "Flags"
            Category.Audit -> "Log"
        }

    fun tone(
        category: Category,
        status: Int?,
        title: String = "",
    ): Tone =
        when {
            category == Category.WebSocket -> if (title.startsWith("WS failed")) Tone.Failed else Tone.Redirect
            category != Category.Http -> Tone.Neutral
            status == null -> Tone.Failed
            status < 300 -> Tone.Success
            status < 400 -> Tone.Redirect
            status < 500 -> Tone.ClientError
            else -> Tone.ServerError
        }

    fun time(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return listOf(local.hour, local.minute, local.second).joinToString(":") { it.toString().padStart(2, '0') }
    }

    fun duration(ms: Long?): String =
        when {
            ms == null -> ""
            ms < 1000 -> "$ms ms"
            else -> "${ms / 1000}.${(ms % 1000 / 100)} s"
        }

    fun size(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024}.${bytes % 1024 * 10 / 1024} KB"
            else -> "${bytes / (1024 * 1024)}.${bytes % (1024 * 1024) * 10 / (1024 * 1024)} MB"
        }

    fun body(
        ref: BodyRef,
        bytes: ByteArray?,
    ): String {
        if (!ref.stored) return "Not stored (${size(ref.size)}, above maxBodySize)"
        if (bytes == null) return "Body no longer available"
        if (!ref.text) return "Binary content, ${size(ref.size)}"
        val text = bytes.decodeToString()
        val looksJson = ref.contentType?.contains("json", true) == true || text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        if (!looksJson) return text
        return runCatching { pretty.encodeToString(Json.parseToJsonElement(text)) }.getOrDefault(text)
    }
}
