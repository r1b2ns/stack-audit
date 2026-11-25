package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackAuditInterceptorTest {
    private class FakeRecorder(
        override val isCapturing: Boolean = true,
        override val maxBodySize: Long = 1024,
    ) : HttpRecorder {
        val recorded = mutableListOf<HttpExchange>()

        override fun record(exchange: HttpExchange) {
            recorded += exchange
        }
    }

    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun client(recorder: FakeRecorder) = OkHttpClient.Builder().audited(recorder).build()

    @Test
    fun capturesExchangeAndKeepsBodyReadable() {
        server.enqueue(
            MockResponse.Builder().code(201).addHeader("Content-Type", "application/json").body("""{"ok":true}""").build(),
        )
        val recorder = FakeRecorder()
        val request =
            Request
                .Builder()
                .url(server.url("/items?q=1"))
                .header("Authorization", "Bearer abc")
                .post("""{"name":"a"}""".toRequestBody("application/json".toMediaType()))
                .build()
        val body = client(recorder).newCall(request).execute().use { it.body.string() }
        assertEquals("""{"ok":true}""", body)
        val exchange = recorder.recorded.single()
        assertEquals("POST", exchange.method)
        assertEquals(201, exchange.status)
        assertTrue(exchange.url.endsWith("/items?q=1"))
        assertEquals("""{"name":"a"}""", exchange.requestBody!!.decodeToString())
        assertEquals("""{"ok":true}""", exchange.responseBody!!.decodeToString())
        assertTrue(exchange.requestHeaders.any { it.name == "Content-Type" && it.value.startsWith("application/json") })
        assertEquals("HTTP/1.1", exchange.protocol)
        assertEquals("okhttp", exchange.source)
    }

    @Test
    fun recordsNetworkFailures() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        val recorder = FakeRecorder()
        assertFailsWith<IOException> {
            client(recorder).newCall(Request.Builder().url(server.url("/down")).build()).execute()
        }
        val exchange = recorder.recorded.single()
        assertNull(exchange.status)
        assertNotNull(exchange.error)
    }

    @Test
    fun skipsBodiesAboveLimit() {
        val big = "x".repeat(100)
        server.enqueue(MockResponse.Builder().body(big).build())
        val recorder = FakeRecorder(maxBodySize = 10)
        val body = client(recorder).newCall(Request.Builder().url(server.url("/big")).build()).execute().use { it.body.string() }
        assertEquals(big, body)
        assertNull(recorder.recorded.single().responseBody)
        assertEquals(100L, recorder.recorded.single().responseBodySize)
    }

    @Test
    fun passesThroughWhenNotCapturing() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val recorder = FakeRecorder(isCapturing = false)
        client(recorder).newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertTrue(recorder.recorded.isEmpty())
    }
}
