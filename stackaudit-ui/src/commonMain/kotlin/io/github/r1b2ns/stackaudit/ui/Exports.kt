package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.AnalyticsPayload
import io.github.r1b2ns.stackaudit.AuditEvent
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.DatabasePayload
import io.github.r1b2ns.stackaudit.FlagPayload
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.MetaPayload
import io.github.r1b2ns.stackaudit.SecretRules
import io.github.r1b2ns.stackaudit.SecretScrubber
import io.github.r1b2ns.stackaudit.StoragePayload
import io.github.r1b2ns.stackaudit.WebSocketPayload
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

internal enum class BatchFormat(
    val title: String,
    val extension: String,
    val mimeType: String,
    val hint: String,
) {
    Text("Text", "txt", "text/plain", "Readable text with the headers and bodies of each request and response, one event after another."),
    Json("JSON", "json", "application/json", "A .json file with every field of each event."),
    Har("HAR", "har", "application/json", "HTTP Archive format: opens in Safari, Chrome, Charles and Proxyman."),
    Csv("CSV", "csv", "text/csv", "One line per event, for spreadsheets. Bodies and headers are left out."),
    ;

    val supportsBodies: Boolean get() = this != Csv
}

internal enum class EventFormat(
    val title: String,
    val extension: String,
    val mimeType: String,
    val hint: String,
) {
    Text("Text", "txt", "text/plain", "The whole request and response: headers and bodies, good for a support ticket or email."),
    Curl("cURL", "sh", "text/plain", "A command to repeat the request in Terminal."),
    Json("JSON", "json", "application/json", "The whole event in .json: request, response and metadata."),
    Har("HAR", "har", "application/json", "HTTP Archive with one entry: opens in Safari, Chrome, Charles and Proxyman."),
}

internal data class ExportEvent(
    val event: AuditEvent,
    val requestBody: String? = null,
    val responseBody: String? = null,
) {
    val http: HttpPayload? get() = event.payload as? HttpPayload
}

internal class Exporter(
    rules: SecretRules = SecretRules.Default,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    val masks: Boolean = true,
) {
    private val scrubber = SecretScrubber(rules)

    private fun maskUrl(url: String): String = if (masks) scrubber.url(url) else url

    private fun maskSql(sql: String): String = if (masks) scrubber.sql(sql) else sql

    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun batch(
        events: List<ExportEvent>,
        format: BatchFormat,
        includeBodies: Boolean,
        total: Int = events.size,
    ): String =
        when (format) {
            BatchFormat.Json -> encode(JsonArray(events.map { jsonEvent(it, includeBodies) }))
            BatchFormat.Har -> har(events, includeBodies)
            BatchFormat.Csv -> csv(events)
            BatchFormat.Text -> textBatch(events, includeBodies, total)
        }

    fun textBatch(
        events: List<ExportEvent>,
        includeBodies: Boolean,
        total: Int = events.size,
    ): String {
        if (total == 1 && events.size == 1) return text(events.first(), includeBodies)
        return events
            .mapIndexed { index, item -> "Event ${index + 1} of $total\n" + text(item, includeBodies) }
            .joinToString("\n\n$SEPARATOR\n\n")
    }

    fun single(
        event: ExportEvent,
        format: EventFormat,
        includeBody: Boolean,
    ): String =
        when (format) {
            EventFormat.Curl -> curl(event, includeBody)
            EventFormat.Json -> encode(jsonEvent(event, includeBody))
            EventFormat.Har -> har(listOf(event), includeBody)
            EventFormat.Text -> text(event, includeBody)
        }

    fun maskedHeaderCount(event: ExportEvent): Int {
        val http = event.http ?: return 0
        return (headers(http.requestHeaders) + headers(http.responseHeaders)).count { Presentation.isMasked(it.value) }
    }

    fun headers(list: List<Header>): List<Header> = if (masks) scrubber.headers(list) else list

    fun url(event: ExportEvent): String = event.http?.let { maskUrl(it.url) } ?: event.event.title

    fun body(
        text: String?,
        contentType: String?,
    ): String? = text?.let { if (masks) scrubber.body(it, contentType) else it }

    fun timestamp(epochMs: Long): String {
        val instant = Instant.fromEpochMilliseconds(epochMs)
        val time = instant.toLocalDateTime(zone)
        val offset = zone.offsetAt(instant).toString()
        val millis = time.nanosecond / 1_000_000
        return buildString {
            append(time.year.toString().padStart(4, '0')).append('-')
            append(two(time.month.ordinal + 1)).append('-').append(two(time.day))
            append('T').append(two(time.hour)).append(':').append(two(time.minute)).append(':').append(two(time.second))
            append('.').append(millis.toString().padStart(3, '0'))
            append(offset)
        }
    }

    fun fileStamp(epochMs: Long): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return "${time.year}${two(time.month.ordinal + 1)}${two(time.day)}-${two(time.hour)}${two(time.minute)}${two(time.second)}"
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    private fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), sorted(element))

    private fun sorted(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
            is JsonArray -> JsonArray(element.map(::sorted))
            else -> element
        }

    private fun string(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    private fun number(value: Number?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    private fun direction(category: Category): String = if (Presentation.isOutbound(category)) "outbound" else "inbound"

    private fun requestSize(event: ExportEvent): Long = event.http?.requestBody?.size ?: 0

    private fun responseSize(event: ExportEvent): Long = event.http?.responseBody?.size ?: 0

    private fun hostAndPath(event: ExportEvent): Pair<String, String> {
        val http = event.http ?: return event.event.title to event.event.source
        return Presentation.hostAndPath(maskUrl(http.url))
    }

    private fun method(event: ExportEvent): String =
        event.http?.method ?: when (event.event.category) {
            Category.WebSocket -> "WS"
            Category.Storage -> "KV"
            Category.Database -> "SQL"
            Category.Analytics -> "EVENT"
            Category.Flag -> "FLAG"
            else -> "LOG"
        }

    private fun headerMap(list: List<Header>): JsonObject =
        JsonObject(
            headers(list)
                .groupBy { it.name }
                .mapValues { (_, values) -> JsonPrimitive(values.joinToString(", ") { it.value }) },
        )

    private fun details(event: AuditEvent): JsonElement =
        when (val payload = event.payload) {
            is HttpPayload -> JsonNull
            is WebSocketPayload ->
                JsonObject(
                    mapOf(
                        "connectionId" to string(payload.connectionId),
                        "url" to string(maskUrl(payload.url)),
                        "event" to string(payload.kind.name.lowercase()),
                        "code" to number(payload.code),
                        "reason" to string(payload.reason),
                        "error" to string(payload.error),
                    ),
                )
            is StoragePayload ->
                JsonObject(
                    mapOf(
                        "store" to string(payload.store),
                        "key" to string(payload.key),
                        "operation" to string(payload.operation.name.lowercase()),
                        "oldValue" to string(payload.oldValue),
                        "newValue" to string(payload.newValue),
                        "valueType" to string(payload.valueType),
                    ),
                )
            is DatabasePayload ->
                JsonObject(
                    mapOf(
                        "database" to string(payload.database),
                        "sql" to string(maskSql(payload.sql)),
                        "arguments" to JsonArray(payload.arguments.map(::string)),
                        "rowsAffected" to number(payload.rowsAffected),
                        "error" to string(payload.error),
                    ),
                )
            is AnalyticsPayload ->
                JsonObject(
                    mapOf(
                        "provider" to string(payload.provider),
                        "name" to string(payload.name),
                        "params" to JsonObject(payload.params.mapValues { string(it.value) }),
                        "validation" to string(payload.validation.name),
                        "issues" to JsonArray(payload.issues.map(::JsonPrimitive)),
                    ),
                )
            is FlagPayload ->
                JsonObject(
                    mapOf(
                        "key" to string(payload.key),
                        "provider" to string(payload.provider),
                        "effective" to string(payload.effective.display),
                        "source" to string(payload.source.name.lowercase()),
                        "default" to string(payload.defaultValue.display),
                        "remote" to string(payload.remoteValue?.display),
                        "override" to string(payload.overrideValue?.display),
                    ),
                )
            is MetaPayload ->
                JsonObject(
                    mapOf(
                        "kind" to string(payload.kind.name),
                        "message" to string(payload.message),
                        "count" to number(payload.count),
                    ),
                )
        }

    private fun jsonEvent(
        item: ExportEvent,
        includeBody: Boolean,
    ): JsonObject {
        val event = item.event
        val http = item.http
        val (host, path) = hostAndPath(item)
        val fields =
            mutableMapOf(
                "id" to string(event.id),
                "time" to string(timestamp(event.timestamp)),
                "kind" to string(event.category.wire),
                "direction" to string(direction(event.category)),
                "category" to string(Presentation.categoryTitle(event.category)),
                "method" to string(method(item)),
                "url" to string(http?.let { maskUrl(it.url) }),
                "host" to string(host),
                "path" to string(path),
                "status" to number(http?.status),
                "statusText" to string(http?.status?.let(Presentation::reason)),
                "error" to string(http?.error),
                "durationMs" to number(event.durationMs),
                "bytesSent" to number(requestSize(item)),
                "bytesReceived" to number(responseSize(item)),
                "connection" to string(http?.protocol),
                "origin" to string(event.source),
                "traceId" to string(event.traceId),
                "requestHeaders" to headerMap(http?.requestHeaders.orEmpty()),
                "responseHeaders" to headerMap(http?.responseHeaders.orEmpty()),
                "requestBody" to string(if (includeBody) body(item.requestBody, http?.requestBody?.contentType) else null),
                "responseBody" to string(if (includeBody) body(item.responseBody, http?.responseBody?.contentType) else null),
            )
        if (http == null) fields["details"] = details(event)
        return JsonObject(fields)
    }

    private fun har(
        events: List<ExportEvent>,
        includeBodies: Boolean,
    ): String {
        val entries =
            events.mapNotNull { item ->
                val http = item.http ?: return@mapNotNull null
                val url = maskUrl(http.url)
                val version = http.protocol?.uppercase()?.takeIf { it.startsWith("HTTP") } ?: "HTTP/1.1"
                val duration = (item.event.durationMs ?: 0).toDouble()
                val requestBody = if (includeBodies) body(item.requestBody, http.requestBody?.contentType) else null
                val responseBody = if (includeBodies) body(item.responseBody, http.responseBody?.contentType) else null
                JsonObject(
                    mapOf(
                        "startedDateTime" to string(timestamp(item.event.timestamp)),
                        "time" to JsonPrimitive(duration),
                        "request" to
                            JsonObject(
                                buildMap {
                                    put("method", string(http.method))
                                    put("url", string(url))
                                    put("httpVersion", string(version))
                                    put("headers", nameValues(headers(http.requestHeaders)))
                                    put("queryString", nameValues(query(url)))
                                    put("cookies", JsonArray(emptyList()))
                                    put("headersSize", JsonPrimitive(-1))
                                    put("bodySize", JsonPrimitive(requestSize(item)))
                                    if (requestBody != null) {
                                        put(
                                            "postData",
                                            JsonObject(
                                                mapOf(
                                                    "mimeType" to string(http.requestBody?.contentType ?: "application/octet-stream"),
                                                    "text" to string(requestBody),
                                                ),
                                            ),
                                        )
                                    }
                                },
                            ),
                        "response" to
                            JsonObject(
                                mapOf(
                                    "status" to JsonPrimitive(http.status ?: 0),
                                    "statusText" to string(http.status?.let(Presentation::reason) ?: http.error.orEmpty()),
                                    "httpVersion" to string(version),
                                    "headers" to nameValues(headers(http.responseHeaders)),
                                    "cookies" to JsonArray(emptyList()),
                                    "content" to
                                        JsonObject(
                                            buildMap {
                                                put("size", JsonPrimitive(responseSize(item)))
                                                put("mimeType", string(http.responseBody?.contentType.orEmpty()))
                                                if (responseBody != null) put("text", string(responseBody))
                                            },
                                        ),
                                    "redirectURL" to string(""),
                                    "headersSize" to JsonPrimitive(-1),
                                    "bodySize" to JsonPrimitive(responseSize(item)),
                                ),
                            ),
                        "cache" to JsonObject(emptyMap()),
                        "timings" to
                            JsonObject(
                                mapOf(
                                    "send" to JsonPrimitive(0),
                                    "wait" to JsonPrimitive(duration),
                                    "receive" to JsonPrimitive(0),
                                ),
                            ),
                        "comment" to string(item.event.source),
                    ),
                )
            }
        val log =
            JsonObject(
                mapOf(
                    "log" to
                        JsonObject(
                            mapOf(
                                "version" to string("1.2"),
                                "creator" to JsonObject(mapOf("name" to string("StackAudit"), "version" to string(VERSION))),
                                "entries" to JsonArray(entries),
                            ),
                        ),
                ),
            )
        return encode(log)
    }

    private fun nameValues(list: List<Header>): JsonArray =
        JsonArray(list.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "value" to JsonPrimitive(it.value))) })

    private fun query(url: String): List<Header> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map {
            Header(it.substringBefore('='), if ('=' in it) it.substringAfter('=') else "")
        }
    }

    fun csv(events: List<ExportEvent>): String {
        val lines = mutableListOf(CSV_HEADER)
        events.forEach { item ->
            val http = item.http
            val (host, path) = hostAndPath(item)
            val status = http?.status?.toString() ?: http?.error ?: ""
            lines +=
                listOf(
                    timestamp(item.event.timestamp),
                    direction(item.event.category),
                    method(item),
                    host,
                    path,
                    status,
                    requestSize(item).toString(),
                    responseSize(item).toString(),
                    Presentation.categoryTitle(item.event.category),
                ).joinToString(",", transform = ::csvField)
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun curl(
        item: ExportEvent,
        includeBody: Boolean,
    ): String {
        val http = item.http ?: return "# cURL is only available for network requests."
        var head = "curl"
        if (http.method != "GET") head += " -X ${http.method}"
        head += " " + quote(maskUrl(http.url))
        val parts = mutableListOf(head)
        headers(http.requestHeaders)
            .filter { it.name.lowercase() !in SKIPPED_CURL_HEADERS }
            .forEach { parts += "-H ${quote("${it.name}: ${it.value}")}" }
        val requestBody = body(item.requestBody, http.requestBody?.contentType)
        if (includeBody && !requestBody.isNullOrEmpty()) parts += "--data-raw ${quote(requestBody)}"
        return parts.joinToString(" \\\n  ")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun text(
        item: ExportEvent,
        includeBody: Boolean,
    ): String {
        val event = item.event
        val http = item.http
        val lines = mutableListOf<String>()
        if (http != null) {
            lines += "${http.method} ${maskUrl(http.url)}"
            var status = Presentation.statusTitle(http.status, http.error)
            event.durationMs?.let { status += " · $it ms" }
            lines += "Status: $status"
        } else {
            lines += "${method(item)} ${event.title}"
            event.durationMs?.let { lines += "Duration: $it ms" }
        }
        lines += "When: ${Presentation.mediumDateTime(event.timestamp, zone)}"
        lines += "Origin: ${event.source}"
        if (http == null) {
            (details(event) as? JsonObject)?.forEach { (key, value) ->
                val shown = detailText(value) ?: return@forEach
                lines += "${key.replaceFirstChar { it.uppercase() }}: $shown"
            }
            if (includeBody) {
                item.requestBody?.takeIf { it.isNotEmpty() }?.let {
                    lines += ""
                    lines += "Message:"
                    lines += body(it, null).orEmpty()
                }
            }
            return lines.joinToString("\n")
        }
        lines += "Sent: ${Presentation.bytes(requestSize(item))} · Received: ${Presentation.bytes(responseSize(item))}"
        section(lines, "Request Headers", headerLines(http.requestHeaders))
        if (includeBody) section(lines, "Request Body", body(item.requestBody, http.requestBody?.contentType))
        section(lines, "Response Headers", headerLines(http.responseHeaders))
        if (includeBody) section(lines, "Response Body", body(item.responseBody, http.responseBody?.contentType))
        return lines.joinToString("\n")
    }

    private fun headerLines(list: List<Header>): String = headers(list).joinToString("\n") { "${it.name}: ${it.value}" }

    private fun section(
        lines: MutableList<String>,
        title: String,
        content: String?,
    ) {
        lines += ""
        lines += "$title:"
        lines += content?.takeIf { it.isNotEmpty() } ?: "(empty)"
    }

    private fun detailText(value: JsonElement): String? =
        when (value) {
            is JsonNull -> null
            is JsonPrimitive -> value.content
            is JsonArray -> if (value.isEmpty()) null else value.toString()
            is JsonObject -> if (value.isEmpty()) null else value.toString()
        }

    companion object {
        const val CSV_HEADER: String = "time,direction,method,host,path,status,sent,received,category"
        const val VERSION: String = "0.1.0"
        val SEPARATOR: String = "-".repeat(48)
        const val UNMASKED_NOTE: String = "Secrets.Show is on, so tokens, passwords and other secrets are stored, shown and exported as they are. Use it only on local debug builds."
        private val SKIPPED_CURL_HEADERS = setOf("content-length", "host", "connection")

        val maskedItems: List<String> =
            listOf(
                "Authorization and Bearer tokens",
                "Cookies and Set-Cookie",
                "access_token, refresh_token and id_token",
                "Passwords, client_secret and API keys",
                "API keys in URL parameters (key=…)",
                "Every field added to the secret rules",
            )
    }
}
