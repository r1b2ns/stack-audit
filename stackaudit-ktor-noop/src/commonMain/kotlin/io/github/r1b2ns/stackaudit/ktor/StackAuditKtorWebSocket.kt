package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder

public suspend fun HttpClient.auditedWebSocket(
    urlString: String,
    request: HttpRequestBuilder.() -> Unit = {},
    recorder: WebSocketRecorder = StackAudit,
    block: suspend DefaultClientWebSocketSession.() -> Unit,
) {
    recorder.hashCode()
    webSocket(urlString, request) { block() }
}

public fun DefaultClientWebSocketSession.audited(
    recorder: WebSocketRecorder = StackAudit,
    source: String = "ktor",
): DefaultClientWebSocketSession = this.also { recorder.hashCode() + source.hashCode() }
