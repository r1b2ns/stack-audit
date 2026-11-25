package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.WebSocketFrame
import io.github.r1b2ns.stackaudit.WebSocketKind
import io.github.r1b2ns.stackaudit.WebSocketRecorder
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackAuditWebSocketTest {
    private class FakeRecorder(
        override val isCapturingWebSockets: Boolean = true,
        override val maxBodySize: Long = 1024,
    ) : WebSocketRecorder {
        val frames = CopyOnWriteArrayList<WebSocketFrame>()

        override fun record(frame: WebSocketFrame) {
            frames += frame
        }
    }

    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun echoServer() {
        server.enqueue(
            MockResponse
                .Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            webSocket.send("echo:$text")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: okio.ByteString,
                        ) {
                            webSocket.send(bytes)
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }
                    },
                ).build(),
        )
    }

    @Test
    fun recordsTheWholeConversation() {
        echoServer()
        val recorder = FakeRecorder()
        val received = CountDownLatch(2)
        val closed = CountDownLatch(1)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: okhttp3.Response,
                ) {
                    webSocket.send("hello")
                    webSocket.send(byteArrayOf(1, 2).toByteString())
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    received.countDown()
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: okio.ByteString,
                ) {
                    received.countDown()
                    webSocket.close(1000, "bye")
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    closed.countDown()
                }
            }
        OkHttpClient().newAuditedWebSocket(Request.Builder().url(server.url("/ws")).build(), listener, recorder)
        assertTrue(received.await(5, TimeUnit.SECONDS))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        val kinds = recorder.frames.map { it.kind }
        assertEquals(WebSocketKind.Open, kinds.first())
        assertTrue(kinds.count { it == WebSocketKind.Sent } == 2, kinds.toString())
        assertTrue(kinds.count { it == WebSocketKind.Received } == 2, kinds.toString())
        assertTrue(WebSocketKind.Closed in kinds)
        assertEquals(1, recorder.frames.map { it.connectionId }.toSet().size)
        assertTrue(recorder.frames.any { it.kind == WebSocketKind.Received && it.data?.decodeToString() == "echo:hello" })
        assertTrue(recorder.frames.any { it.kind == WebSocketKind.Sent && !it.isText })
    }

    @Test
    fun recordsFailures() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val recorder = FakeRecorder()
        val failed = CountDownLatch(1)
        OkHttpClient().newAuditedWebSocket(
            Request.Builder().url(server.url("/ws")).build(),
            object : WebSocketListener() {
                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: okhttp3.Response?,
                ) {
                    failed.countDown()
                }
            },
            recorder,
        )
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(WebSocketKind.Failed, recorder.frames.single().kind)
    }

    @Test
    fun passesThroughWhenNotCapturing() {
        echoServer()
        val recorder = FakeRecorder(isCapturingWebSockets = false)
        val socket = OkHttpClient().newAuditedWebSocket(Request.Builder().url(server.url("/ws")).build(), object : WebSocketListener() {}, recorder)
        assertTrue(socket !is AuditedWebSocket)
        socket.cancel()
        assertTrue(recorder.frames.isEmpty())
    }
}
