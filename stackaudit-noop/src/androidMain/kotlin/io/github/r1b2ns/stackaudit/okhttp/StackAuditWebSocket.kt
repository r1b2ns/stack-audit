package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

public fun OkHttpClient.newAuditedWebSocket(
    request: Request,
    listener: WebSocketListener,
    recorder: WebSocketRecorder = StackAudit,
): WebSocket = newWebSocket(request, listener).also { recorder.hashCode() }
