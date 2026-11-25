package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.WebSocketFrame
import io.github.r1b2ns.stackaudit.WebSocketKind
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class StackAuditKtorWebSocketTest {
    private class FakeRecorder(
        override val isCapturingWebSockets: Boolean = true,
        override val maxBodySize: Long = 1024,
    ) : WebSocketRecorder {
        val frames = CopyOnWriteArrayList<WebSocketFrame>()

        override fun record(frame: WebSocketFrame) {
            frames += frame
        }
    }

    private val server =
        embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/echo") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text("echo:" + frame.readText()))
                    }
                }
            }
        }.start(wait = false)

    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
    }

    @Test
    fun recordsFramesInBothDirections() =
        runBlocking {
            val recorder = FakeRecorder()
            val client = HttpClient(CIO) { install(WebSockets) }
            var reply = ""
            client.auditedWebSocket("ws://127.0.0.1:$port/echo?token=x", recorder = recorder) {
                send(Frame.Text("hello"))
                reply = (incoming.receive() as Frame.Text).readText()
                close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                for (frame in incoming) Unit
            }
            client.close()
            assertEquals("echo:hello", reply)
            val kinds = recorder.frames.map { it.kind }
            assertEquals(WebSocketKind.Open, kinds.first())
            assertTrue(recorder.frames.any { it.kind == WebSocketKind.Sent && it.data?.decodeToString() == "hello" }, kinds.toString())
            assertTrue(recorder.frames.any { it.kind == WebSocketKind.Received && it.data?.decodeToString() == "echo:hello" }, kinds.toString())
            assertTrue(recorder.frames.any { it.kind == WebSocketKind.Closing && it.code == 1000 }, kinds.toString())
            assertEquals(1, recorder.frames.map { it.connectionId }.toSet().size)
            assertTrue(recorder.frames.all { it.url.startsWith("ws://127.0.0.1:$port/echo") })
        }

    @Test
    fun leavingTheBlockIsAClosureNotAFailure() =
        runBlocking {
            val recorder = FakeRecorder()
            val client = HttpClient(CIO) { install(WebSockets) }
            client.auditedWebSocket("ws://127.0.0.1:$port/echo", recorder = recorder) {
                send(Frame.Text("hi"))
                incoming.receive()
            }
            client.close()
            val kinds = recorder.frames.map { it.kind }
            assertTrue(WebSocketKind.Failed !in kinds, kinds.toString())
        }

    @Test
    fun returnsTheSameSessionWhenNotCapturing() =
        runBlocking {
            val recorder = FakeRecorder(isCapturingWebSockets = false)
            val client = HttpClient(CIO) { install(WebSockets) }
            client.auditedWebSocket("ws://127.0.0.1:$port/echo", recorder = recorder) {
                send(Frame.Text("hi"))
                incoming.receive()
            }
            client.close()
            assertTrue(recorder.frames.isEmpty())
        }
}
