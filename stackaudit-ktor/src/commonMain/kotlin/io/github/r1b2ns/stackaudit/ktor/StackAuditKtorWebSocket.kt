package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketFrame
import io.github.r1b2ns.stackaudit.WebSocketKind
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.date.getTimeMillis
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public suspend fun HttpClient.auditedWebSocket(
    urlString: String,
    request: HttpRequestBuilder.() -> Unit = {},
    recorder: WebSocketRecorder = StackAudit,
    block: suspend DefaultClientWebSocketSession.() -> Unit,
) {
    webSocket(urlString, request) { audited(recorder).block() }
}

@OptIn(ExperimentalUuidApi::class)
public fun DefaultClientWebSocketSession.audited(
    recorder: WebSocketRecorder = StackAudit,
    source: String = "ktor",
): DefaultClientWebSocketSession {
    if (!recorder.isCapturingWebSockets) return this
    val tap = KtorWebSocketTap(recorder, source, Uuid.random().toString(), call.request.url.toString())
    tap.record(WebSocketKind.Open)
    return DefaultClientWebSocketSession(call, AuditedWebSocketSession(this, tap))
}

internal class KtorWebSocketTap(
    private val recorder: WebSocketRecorder,
    private val source: String,
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
        recorder.record(
            WebSocketFrame(
                source = source,
                connectionId = connectionId,
                url = url,
                kind = kind,
                timestamp = getTimeMillis(),
                data = data?.takeIf { it.size <= recorder.maxBodySize },
                isText = isText,
                code = code,
                reason = reason,
                error = error,
            ),
        )
    }

    fun record(
        frame: Frame,
        incoming: Boolean,
    ) {
        val direction = if (incoming) WebSocketKind.Received else WebSocketKind.Sent
        when (frame) {
            is Frame.Text -> record(direction, frame.data.copyOf())
            is Frame.Binary -> record(direction, frame.data.copyOf(), isText = false)
            is Frame.Ping -> record(WebSocketKind.Ping)
            is Frame.Pong -> record(WebSocketKind.Pong)
            is Frame.Close -> {
                val reason = (frame.copy() as Frame.Close).readReason()
                record(WebSocketKind.Closing, code = reason?.code?.toInt(), reason = reason?.message)
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AuditedWebSocketSession(
    private val delegate: DefaultWebSocketSession,
    private val tap: KtorWebSocketTap,
) : DefaultWebSocketSession by delegate {
    override val incoming: ReceiveChannel<Frame> =
        delegate.produce {
            try {
                for (frame in delegate.incoming) {
                    tap.record(frame, incoming = true)
                    send(frame)
                }
                val reason: CloseReason? = runCatching { delegate.closeReason.await() }.getOrNull()
                tap.record(WebSocketKind.Closed, code = reason?.code?.toInt(), reason = reason?.message)
            } catch (error: Throwable) {
                tap.record(WebSocketKind.Failed, error = (error::class.simpleName ?: "Error") + (error.message?.let { ": $it" } ?: ""))
                throw error
            }
        }

    private val outbox = Channel<Frame>(Channel.UNLIMITED)

    override val outgoing: SendChannel<Frame> = outbox

    init {
        delegate.launch {
            for (frame in outbox) {
                tap.record(frame, incoming = false)
                delegate.outgoing.send(frame)
            }
        }
    }

    override suspend fun send(frame: Frame) {
        outbox.send(frame)
    }
}
