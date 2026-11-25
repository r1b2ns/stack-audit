package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.StackAudit
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException

public class StackAuditKtorConfig {
    public var source: String = "ktor"
    public var recorder: HttpRecorder = StackAudit
}

public val StackAuditKtor: ClientPlugin<StackAuditKtorConfig> =
    createClientPlugin("StackAudit", ::StackAuditKtorConfig) {
        val recorder = pluginConfig.recorder
        val source = pluginConfig.source
        on(Send) { request ->
            if (!recorder.isCapturing) return@on proceed(request)
            val started = getTimeMillis()
            val content = request.body as? OutgoingContent
            val requestBody = content?.let(::bytesOf)
            val requestHeaders = headers(request.headers.build()) + listOfNotNull(content?.contentType?.let { Header("Content-Type", it.toString()) })
            val call =
                try {
                    proceed(request)
                } catch (error: Throwable) {
                    if (error !is CancellationException) {
                        recorder.record(
                            exchange(source, request, requestHeaders, requestBody, content, started).copyFailure(error),
                        )
                    }
                    throw error
                }
            capture(call, recorder, source, request, requestHeaders, requestBody, content, started)
        }
    }

private suspend fun capture(
    call: HttpClientCall,
    recorder: HttpRecorder,
    source: String,
    request: HttpRequestBuilder,
    requestHeaders: List<Header>,
    requestBody: ByteArray?,
    content: OutgoingContent?,
    started: Long,
): HttpClientCall {
    val response = call.response
    val length = response.contentLength()
    val streaming = response.contentType()?.match(ContentType.Text.EventStream) == true
    val skip = streaming || (length != null && length > recorder.maxBodySize)
    val result = if (skip) call else runCatching { call.save() }.getOrDefault(call)
    val read = if (skip || result === call) null else runCatching { result.response.bodyAsBytes() }.getOrNull()
    val body = read?.takeIf { it.size <= recorder.maxBodySize }
    recorder.record(
        HttpExchange(
            source = source,
            method = request.method.value,
            url = call.request.url.toString(),
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            requestContentType = content?.contentType?.toString(),
            status = response.status.value,
            responseHeaders = headers(response.headers),
            responseBody = body,
            responseContentType = response.contentType()?.toString(),
            startedAt = started,
            durationMs = getTimeMillis() - started,
            protocol = response.version.toString(),
            requestBodySize = content?.contentLength ?: requestBody?.size?.toLong(),
            responseBodySize = length ?: read?.size?.toLong(),
        ),
    )
    return result
}

private class Failed(
    val base: HttpExchange,
)

private fun exchange(
    source: String,
    request: HttpRequestBuilder,
    requestHeaders: List<Header>,
    requestBody: ByteArray?,
    content: OutgoingContent?,
    started: Long,
): Failed =
    Failed(
        HttpExchange(
            source = source,
            method = request.method.value,
            url = request.url.buildString(),
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            requestContentType = content?.contentType?.toString(),
            status = null,
            responseHeaders = emptyList(),
            responseBody = null,
            responseContentType = null,
            startedAt = started,
            durationMs = 0,
        ),
    )

private fun Failed.copyFailure(error: Throwable): HttpExchange =
    HttpExchange(
        source = base.source,
        method = base.method,
        url = base.url,
        requestHeaders = base.requestHeaders,
        requestBody = base.requestBody,
        requestContentType = base.requestContentType,
        status = null,
        responseHeaders = emptyList(),
        responseBody = null,
        responseContentType = null,
        startedAt = base.startedAt,
        durationMs = getTimeMillis() - base.startedAt,
        error = (error::class.simpleName ?: "Error") + (error.message?.let { ": $it" } ?: ""),
    )

private fun bytesOf(content: OutgoingContent): ByteArray? =
    when (content) {
        is TextContent -> content.text.encodeToByteArray()
        is OutgoingContent.ByteArrayContent -> content.bytes()
        else -> null
    }

private fun headers(headers: Headers): List<Header> = headers.entries().flatMap { (name, values) -> values.map { Header(name, it) } }
