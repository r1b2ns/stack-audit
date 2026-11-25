package io.github.r1b2ns.stackaudit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Instant

internal object HarExport {
    const val CREATOR_VERSION = "0.1.0"

    suspend fun build(
        events: List<AuditEvent>,
        scrubber: SecretScrubber?,
        loadBody: suspend (BodyRef) -> ByteArray?,
    ): String {
        val entries =
            events.mapNotNull { event ->
                val http = event.payload as? HttpPayload ?: return@mapNotNull null
                entry(event, http, scrubber, loadBody)
            }
        val har =
            buildJsonObject {
                put(
                    "log",
                    buildJsonObject {
                        put("version", "1.2")
                        put(
                            "creator",
                            buildJsonObject {
                                put("name", "StackAudit")
                                put("version", CREATOR_VERSION)
                            },
                        )
                        put("entries", JsonArray(entries))
                    },
                )
            }
        return har.toString()
    }

    private suspend fun entry(
        event: AuditEvent,
        http: HttpPayload,
        scrubber: SecretScrubber?,
        loadBody: suspend (BodyRef) -> ByteArray?,
    ): JsonObject {
        val url = scrubber?.url(http.url) ?: http.url
        val requestHeaders = scrubber?.headers(http.requestHeaders) ?: http.requestHeaders
        val responseHeaders = scrubber?.headers(http.responseHeaders) ?: http.responseHeaders
        val version = http.protocol ?: "HTTP/1.1"
        val requestText = http.requestBody?.let { content(it, scrubber, loadBody) }
        val responseText = http.responseBody?.let { content(it, scrubber, loadBody) }
        return buildJsonObject {
            put("startedDateTime", Instant.fromEpochMilliseconds(event.timestamp).toString())
            put("time", event.durationMs ?: 0)
            put(
                "request",
                buildJsonObject {
                    put("method", http.method)
                    put("url", url)
                    put("httpVersion", version)
                    put("cookies", JsonArray(emptyList()))
                    put("headers", headers(requestHeaders))
                    put("queryString", query(url))
                    put("headersSize", -1)
                    put("bodySize", http.requestBody?.size ?: 0)
                    if (http.requestBody != null) {
                        put(
                            "postData",
                            buildJsonObject {
                                put("mimeType", http.requestBody.contentType ?: "")
                                put("text", requestText?.first ?: "")
                            },
                        )
                    }
                },
            )
            put(
                "response",
                buildJsonObject {
                    put("status", http.status ?: 0)
                    put("statusText", http.error ?: "")
                    put("httpVersion", version)
                    put("cookies", JsonArray(emptyList()))
                    put("headers", headers(responseHeaders))
                    put(
                        "content",
                        buildJsonObject {
                            put("size", http.responseBody?.size ?: 0)
                            put("mimeType", http.responseBody?.contentType ?: "")
                            responseText?.let { (text, encoding) ->
                                put("text", text)
                                encoding?.let { put("encoding", it) }
                            }
                        },
                    )
                    put("redirectURL", "")
                    put("headersSize", -1)
                    put("bodySize", http.responseBody?.size ?: -1)
                },
            )
            put("cache", JsonObject(emptyMap()))
            put(
                "timings",
                buildJsonObject {
                    put("send", 0)
                    put("wait", event.durationMs ?: 0)
                    put("receive", 0)
                },
            )
        }
    }

    private suspend fun content(
        ref: BodyRef,
        scrubber: SecretScrubber?,
        loadBody: suspend (BodyRef) -> ByteArray?,
    ): Pair<String, String?>? {
        val bytes = loadBody(ref) ?: return null
        if (!ref.text) return Base64.encode(bytes) to "base64"
        val text = bytes.decodeToString()
        return (scrubber?.body(text, ref.contentType) ?: text) to null
    }

    private fun headers(list: List<Header>) =
        buildJsonArray {
            list.forEach { header ->
                add(
                    buildJsonObject {
                        put("name", header.name)
                        put("value", header.value)
                    },
                )
            }
        }

    private fun query(url: String) =
        buildJsonArray {
            UrlParts.parse(url).query?.split('&')?.filter { it.isNotEmpty() }?.forEach { pair ->
                add(
                    buildJsonObject {
                        put("name", pair.substringBefore('='))
                        put("value", if ('=' in pair) pair.substringAfter('=') else "")
                    },
                )
            }
        }
}

