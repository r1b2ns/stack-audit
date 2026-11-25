package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketFrame
import io.github.r1b2ns.stackaudit.WebSocketKind
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID

public fun OkHttpClient.newAuditedWebSocket(
    request: Request,
    listener: WebSocketListener,
    recorder: WebSocketRecorder = StackAudit,
): WebSocket {
    if (!recorder.isCapturingWebSockets) return newWebSocket(request, listener)
    val tap = WebSocketTap(recorder, UUID.randomUUID().toString(), request.url.toString())
    val holder = AuditedListener(listener, tap)
    val socket = AuditedWebSocket(newWebSocket(request, holder), tap)
    holder.audited = socket
    return socket
}

internal class WebSocketTap(
    private val recorder: WebSocketRecorder,
    private val connectionId: String,
    private val url: String,
) {
    fun record(
        kind: WebSocketKind,
        data: ByteArray? = null,
        isText: Boolean = true,
        code: Int? = null,
        reason: String? = null,
        error: String? = null,
    ) {
        val limited = data?.takeIf { it.size <= recorder.maxBodySize }
        recorder.record(
            WebSocketFrame(
                source = "okhttp",
                connectionId = connectionId,
                url = url,
                kind = kind,
                timestamp = System.currentTimeMillis(),
                data = limited,
                isText = isText,
                code = code,
                reason = reason,
                error = error,
            ),
        )
    }
}

internal class AuditedWebSocket(
    private val delegate: WebSocket,
    private val tap: WebSocketTap,
) : WebSocket by delegate {
    override fun send(text: String): Boolean {
        tap.record(WebSocketKind.Sent, text.encodeToByteArray())
        return delegate.send(text)
    }

    override fun send(bytes: ByteString): Boolean {
        tap.record(WebSocketKind.Sent, bytes.toByteArray(), isText = false)
        return delegate.send(bytes)
    }

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean {
        tap.record(WebSocketKind.Closing, code = code, reason = reason)
        return delegate.close(code, reason)
    }
}

internal class AuditedListener(
    private val delegate: WebSocketListener,
    private val tap: WebSocketTap,
) : WebSocketListener() {
    @Volatile
    var audited: WebSocket? = null

    private fun socket(raw: WebSocket): WebSocket = audited ?: raw

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        tap.record(WebSocketKind.Open)
        delegate.onOpen(socket(webSocket), response)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        tap.record(WebSocketKind.Received, text.encodeToByteArray())
        delegate.onMessage(socket(webSocket), text)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        tap.record(WebSocketKind.Received, bytes.toByteArray(), isText = false)
        delegate.onMessage(socket(webSocket), bytes)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        tap.record(WebSocketKind.Closing, code = code, reason = reason)
        delegate.onClosing(socket(webSocket), code, reason)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        tap.record(WebSocketKind.Closed, code = code, reason = reason)
        delegate.onClosed(socket(webSocket), code, reason)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        tap.record(WebSocketKind.Failed, error = t::class.java.simpleName + (t.message?.let { ": $it" } ?: ""))
        delegate.onFailure(socket(webSocket), t, response)
    }
}
