package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.FlagState
import io.github.r1b2ns.stackaudit.FlagValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong
import kotlin.time.Instant

internal enum class Tone { Blue, Orange, Purple, Green, Red, Gray }

internal enum class Segment(val title: String) {
    Events("Events"),
    Destinations("Destinations"),
}

internal enum class Tab(val title: String) {
    Requests("Requests"),
    Storage("Storage"),
    Flags("Flags"),
    Settings("Settings"),
}

internal val RequestCategories: Set<Category> = setOf(Category.Http, Category.WebSocket, Category.Analytics)

internal val StorageCategories: Set<Category> = setOf(Category.Storage, Category.Database)

internal enum class StorageLimit(val bytes: Long, val title: String) {
    Small(200_000_000, "200 MB"),
    Medium(500_000_000, "500 MB"),
    Large(1_000_000_000, "1 GB"),
    ;

    companion object {
        fun of(bytes: Long): StorageLimit? = entries.firstOrNull { it.bytes == bytes }
    }
}

internal enum class StorageFilter(val title: String) {
    All("All"),
    Storage("Storage"),
    Database("Database"),
    ;

    fun matches(event: AuditEventSummary): Boolean =
        when (this) {
            All -> event.category in StorageCategories
            Storage -> event.category == Category.Storage
            Database -> event.category == Category.Database
        }
}

internal enum class Filter(val title: String) {
    All("All"),
    Network("Network"),
    WebSocket("WebSocket"),
    Analytics("Analytics"),
    Errors("Errors"),
    ;

    fun matches(event: AuditEventSummary): Boolean =
        when (this) {
            All -> event.category in RequestCategories
            Errors -> event.category in RequestCategories && !Presentation.isSuccess(event)
            else -> event.category == category
        }

    val category: Category?
        get() =
            when (this) {
                Network -> Category.Http
                WebSocket -> Category.WebSocket
                Analytics -> Category.Analytics
                All, Errors -> null
            }
}

internal enum class RecordFilter(val title: String) {
    All("All"),
    Flags("Flags"),
    Log("Log"),
    ;

    fun matches(event: AuditEventSummary): Boolean =
        when (this) {
            All -> event.category == Category.Flag || event.category == Category.Audit
            Flags -> event.category == Category.Flag
            Log -> event.category == Category.Audit
        }
}

internal data class DayGroup(
    val title: String,
    val events: List<AuditEventSummary>,
)

internal data class TodayStats(
    val requests: Int,
    val destinations: Int,
    val sent: Long,
    val received: Long,
)

internal data class Destination(
    val host: String,
    val requests: Int,
    val sent: Long,
    val received: Long,
    val lastSeen: Long,
)

internal sealed interface BodyContent {
    data class Text(
        val text: String,
    ) : BodyContent

    data class Note(
        val text: String,
    ) : BodyContent
}

internal object Presentation {
    private val pretty = Json { prettyPrint = true }

    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private val reasons =
        mapOf(
            100 to "Continue", 101 to "Switching Protocols", 200 to "OK", 201 to "Created", 202 to "Accepted",
            203 to "Non-Authoritative Information", 204 to "No Content", 205 to "Reset Content", 206 to "Partial Content",
            300 to "Multiple Choices", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other", 304 to "Not Modified",
            307 to "Temporary Redirect", 308 to "Permanent Redirect", 400 to "Bad Request", 401 to "Unauthorized",
            402 to "Payment Required", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
            406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 410 to "Gone", 412 to "Precondition Failed",
            413 to "Payload Too Large", 415 to "Unsupported Media Type", 422 to "Unprocessable Entity", 429 to "Too Many Requests",
            500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
            504 to "Gateway Timeout",
        )

    fun categoryTitle(category: Category): String =
        when (category) {
            Category.Http -> "Network"
            Category.WebSocket -> "WebSocket"
            Category.Storage -> "Storage"
            Category.Database -> "Database"
            Category.Analytics -> "Analytics"
            Category.Flag -> "Flags"
            Category.Audit -> "Log"
        }

    fun categoryTone(category: Category): Tone =
        when (category) {
            Category.Http, Category.WebSocket -> Tone.Blue
            Category.Storage, Category.Database -> Tone.Green
            Category.Analytics -> Tone.Purple
            Category.Flag -> Tone.Orange
            Category.Audit -> Tone.Gray
        }

    fun isOutbound(category: Category): Boolean = category == Category.Http || category == Category.WebSocket || category == Category.Analytics

    fun isSuccess(event: AuditEventSummary): Boolean = isSuccess(event.category, event.status, event.title)

    fun isSuccess(
        category: Category,
        status: Int?,
        title: String,
    ): Boolean =
        when (category) {
            Category.Http -> status != null && status in 200..399
            Category.WebSocket -> !title.startsWith("WS failed")
            Category.Analytics -> !(title.endsWith("(invalid)") || title.endsWith("(not in plan)"))
            else -> true
        }

    fun method(event: AuditEventSummary): String =
        when (event.category) {
            Category.Http -> event.method ?: "HTTP"
            Category.WebSocket -> "WS"
            Category.Storage -> "KV"
            Category.Database -> "SQL"
            Category.Analytics -> "EVENT"
            Category.Flag -> "FLAG"
            Category.Audit -> "LOG"
        }

    fun headline(event: AuditEventSummary): String = if (event.category == Category.Http) event.host ?: event.title else event.title

    fun path(event: AuditEventSummary): String {
        if (event.category != Category.Http) return event.source
        val method = event.method ?: return event.title
        val host = event.host ?: return event.title
        val rest = event.title.removePrefix("$method ").removePrefix(host)
        return rest.substringBefore(' ').ifEmpty { "/" }
    }

    fun reason(code: Int): String? = reasons[code]

    fun statusText(event: AuditEventSummary): String =
        when (event.category) {
            Category.Http -> {
                val code = event.status
                when {
                    code == null -> "Failed"
                    code >= 400 -> listOfNotNull(code.toString(), reason(code)).joinToString(" ")
                    else -> code.toString()
                }
            }
            Category.WebSocket -> if (isSuccess(event)) "Delivered" else "Failed"
            Category.Analytics -> if (isSuccess(event)) "Valid" else "Invalid"
            Category.Storage -> "Written"
            Category.Database -> "Executed"
            Category.Flag -> "Read"
            Category.Audit -> "Recorded"
        }

    fun statusTitle(
        status: Int?,
        error: String?,
    ): String =
        when {
            status != null -> listOfNotNull(status.toString(), reason(status)).joinToString(" ")
            error != null -> "Failed"
            else -> "—"
        }

    fun sizes(event: AuditEventSummary): String =
        when {
            event.category == Category.Http -> "↑ ${bytes(event.requestSize ?: 0)} · ↓ ${bytes(event.responseSize ?: 0)}"
            event.durationMs != null -> duration(event.durationMs)
            else -> ""
        }

    fun bytes(count: Long): String {
        if (count < 1000) return "$count bytes"
        val units = listOf("KB" to 0, "MB" to 1, "GB" to 2, "TB" to 2)
        var value = count.toDouble()
        for ((unit, decimals) in units) {
            value /= 1000.0
            if (value < 999.5 || unit == "TB") return "${decimal(value, decimals)} $unit"
        }
        return "$count bytes"
    }

    private fun decimal(
        value: Double,
        decimals: Int,
    ): String {
        if (decimals == 0) return value.roundToLong().toString()
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (value * factor).roundToLong()
        val whole = scaled / factor
        val fraction = (scaled % factor).toString().padStart(decimals, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    fun duration(ms: Long?): String = if (ms == null) "" else "$ms ms"

    private fun local(
        epochMs: Long,
        zone: TimeZone,
    ): LocalDateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)

    private fun clock(time: LocalDateTime): Pair<String, String> {
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        return "$hour:${time.minute.toString().padStart(2, '0')}" to if (time.hour < 12) "AM" else "PM"
    }

    fun shortTime(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val (hm, period) = clock(local(epochMs, zone))
        return "$hm $period"
    }

    fun clockTime(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val time = local(epochMs, zone)
        val (hm, period) = clock(time)
        return "$hm:${time.second.toString().padStart(2, '0')} $period"
    }

    fun shortDate(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val date = local(epochMs, zone).date
        return "${months[date.month.ordinal].take(3)} ${date.day}, ${date.year}"
    }

    fun dateTime(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val time = local(epochMs, zone)
        val (hm, period) = clock(time)
        val seconds = time.second.toString().padStart(2, '0')
        val millis = (epochMs % 1000).let { if (it < 0) it + 1000 else it }.toString().padStart(3, '0')
        return "${shortDate(epochMs, zone)} at $hm:$seconds.$millis $period"
    }

    fun mediumDateTime(
        epochMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val time = local(epochMs, zone)
        val (hm, period) = clock(time)
        return "${shortDate(epochMs, zone)} at $hm:${time.second.toString().padStart(2, '0')} $period"
    }

    fun dayTitle(
        epochMs: Long,
        now: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val day = local(epochMs, zone).date
        val today = local(now, zone).date
        return when (day) {
            today -> "Today"
            today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
            else -> longDay(day)
        }
    }

    private fun longDay(date: LocalDate): String {
        val weekday = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$weekday, ${months[date.month.ordinal]} ${date.day}"
    }

    fun groupByDay(
        events: List<AuditEventSummary>,
        now: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<DayGroup> =
        events
            .groupBy { local(it.timestamp, zone).date }
            .entries
            .sortedByDescending { it.key }
            .map { (_, items) -> DayGroup(dayTitle(items.first().timestamp, now, zone), items) }

    fun today(
        events: List<AuditEventSummary>,
        now: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): TodayStats {
        val today = local(now, zone).date
        val http = events.filter { it.category == Category.Http && local(it.timestamp, zone).date == today }
        return TodayStats(
            requests = http.size,
            destinations = http.mapNotNull { it.host }.toSet().size,
            sent = http.sumOf { it.requestSize ?: 0 },
            received = http.sumOf { it.responseSize ?: 0 },
        )
    }

    fun destinations(events: List<AuditEventSummary>): List<Destination> =
        events
            .filter { it.category == Category.Http && it.host != null }
            .groupBy { it.host!! }
            .map { (host, items) ->
                Destination(
                    host = host,
                    requests = items.size,
                    sent = items.sumOf { it.requestSize ?: 0 },
                    received = items.sumOf { it.responseSize ?: 0 },
                    lastSeen = items.maxOf { it.timestamp },
                )
            }.sortedWith(compareByDescending<Destination> { it.requests }.thenBy { it.host })

    fun matchesSearch(
        event: AuditEventSummary,
        query: String,
    ): Boolean {
        val fields =
            listOfNotNull(
                event.title,
                event.host,
                event.method,
                event.source,
                method(event),
                path(event),
                categoryTitle(event.category),
                statusText(event),
                event.status?.toString(),
            )
        return matchesTerms(fields, query)
    }

    fun matchesSearch(
        state: FlagState,
        query: String,
    ): Boolean =
        matchesTerms(
            listOf(
                state.key,
                state.provider,
                state.source.name,
                state.effective.display,
                flagOrigin(state),
            ),
            query,
        )

    fun matchesTerms(
        fields: List<String>,
        query: String,
    ): Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        return terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }
    }

    fun isMasked(value: String): Boolean = "••••" in value

    fun hostAndPath(url: String): Pair<String, String> {
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore('/').substringBefore('?')
        val path = afterScheme.removePrefix(host).ifEmpty { "/" }
        return host to path
    }

    fun shortType(contentType: String?): String? =
        contentType
            ?.substringBefore(';')
            ?.substringAfter('/')
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }

    fun bodyMeta(ref: BodyRef): String = listOfNotNull(bytes(ref.size), shortType(ref.contentType)).joinToString(" · ")

    fun body(
        ref: BodyRef,
        data: ByteArray?,
    ): BodyContent {
        if (ref.size == 0L) return BodyContent.Note("No body.")
        if (!ref.stored) return BodyContent.Note("Not stored: ${bytes(ref.size)}, above maxBodySize.")
        if (data == null) return BodyContent.Note("Not available: the body is no longer stored.")
        if (data.isEmpty()) return BodyContent.Note("Not available: the body couldn't be read on this device.")
        if (!ref.text) return BodyContent.Note("Binary content (${bytes(ref.size)}).")
        return BodyContent.Text(text(ref.contentType, data.decodeToString()))
    }

    fun text(
        contentType: String?,
        text: String,
    ): String {
        val looksJson = contentType?.contains("json", true) == true || text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        if (!looksJson) return text
        return runCatching { pretty.encodeToString(Json.parseToJsonElement(text)) }.getOrDefault(text)
    }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    fun parseOverride(
        current: FlagValue,
        text: String,
    ): FlagValue? =
        when (current) {
            is FlagValue.Bool -> text.trim().lowercase().toBooleanStrictOrNull()?.let { FlagValue.Bool(it) }
            is FlagValue.Number -> text.trim().toDoubleOrNull()?.let { FlagValue.Number(it) }
            is FlagValue.Text -> FlagValue.Text(text)
        }

    fun eventCount(count: Int): String = "$count events"

    fun editableText(value: FlagValue): String = value.display

    fun kindTitle(value: FlagValue): String =
        when (value) {
            is FlagValue.Bool -> "true or false"
            is FlagValue.Number -> "number"
            is FlagValue.Text -> "text"
        }

    fun flagOrigin(state: FlagState): String =
        buildList {
            add("Default ${state.defaultValue.display.ifEmpty { "\"\"" }}")
            add("Remote ${state.remoteValue?.display?.ifEmpty { "\"\"" } ?: "—"}")
            state.overrideValue?.let { add("Override ${it.display.ifEmpty { "\"\"" }}") }
        }.joinToString(" · ")
}
