package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.StackAudit
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

public class StackAuditInterceptor
    @JvmOverloads
    constructor(
        private val recorder: HttpRecorder = StackAudit,
        private val source: String = "okhttp",
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!recorder.isCapturing) return chain.proceed(request)
            val limit = recorder.maxBodySize
            val started = System.currentTimeMillis()
            val startedNs = System.nanoTime()
            val requestBody = runCatching { requestBytes(request, limit) }.getOrNull()
            val requestHeaders = headers(request.headers) + contentTypeHeader(request)
            val response =
                try {
                    chain.proceed(request)
                } catch (error: IOException) {
                    recorder.record(
                        HttpExchange(
                            source = source,
                            method = request.method,
                            url = request.url.toString(),
                            requestHeaders = requestHeaders,
                            requestBody = requestBody,
                            requestContentType = request.body?.contentType()?.toString(),
                            status = null,
                            responseHeaders = emptyList(),
                            responseBody = null,
                            responseContentType = null,
                            startedAt = started,
                            durationMs = elapsed(startedNs),
                            error = error::class.java.simpleName + (error.message?.let { ": $it" } ?: ""),
                            requestBodySize = request.body?.contentLength()?.takeIf { it >= 0 },
                        ),
                    )
                    throw error
                }
            val contentType = response.body.contentType()
            val streaming = contentType?.subtype == "event-stream"
            val declared = response.body.contentLength().takeIf { it >= 0 }
            val peeked =
                if (streaming || (declared != null && declared > limit)) {
                    null
                } else {
                    runCatching { response.peekBody(limit + 1).bytes() }.getOrNull()
                }
            recorder.record(
                HttpExchange(
                    source = source,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    requestContentType = request.body?.contentType()?.toString(),
                    status = response.code,
                    responseHeaders = headers(response.headers),
                    responseBody = peeked?.takeIf { it.size <= limit },
                    responseContentType = contentType?.toString(),
                    startedAt = started,
                    durationMs = elapsed(startedNs),
                    protocol = response.protocol.toString().uppercase(),
                    requestBodySize = request.body?.contentLength()?.takeIf { it >= 0 },
                    responseBodySize = declared ?: peeked?.size?.toLong(),
                ),
            )
            return response
        }

        private fun requestBytes(
            request: Request,
            limit: Long,
        ): ByteArray? {
            val body = request.body ?: return null
            if (body.isDuplex() || body.isOneShot() || body.contentLength() > limit) return null
            return Buffer().also(body::writeTo).readByteArray()
        }

        private fun contentTypeHeader(request: Request): List<Header> {
            if (request.header("Content-Type") != null) return emptyList()
            return listOfNotNull(request.body?.contentType()?.let { Header("Content-Type", it.toString()) })
        }

        private fun headers(headers: Headers): List<Header> = headers.map { (name, value) -> Header(name, value) }

        private fun elapsed(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000
    }

public fun OkHttpClient.Builder.audited(recorder: HttpRecorder = StackAudit): OkHttpClient.Builder =
    addInterceptor(StackAuditInterceptor(recorder))
