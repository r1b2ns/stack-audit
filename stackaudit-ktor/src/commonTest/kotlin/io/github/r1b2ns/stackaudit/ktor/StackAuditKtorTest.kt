package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackAuditKtorTest {
    private class FakeRecorder(
        override val isCapturing: Boolean = true,
        override val maxBodySize: Long = 1024,
    ) : HttpRecorder {
        val recorded = mutableListOf<HttpExchange>()

        override fun record(exchange: HttpExchange) {
            recorded += exchange
        }
    }

    private fun client(
        recorder: FakeRecorder,
        body: String = """{"ok":true}""",
        fail: Boolean = false,
    ) = HttpClient(
        MockEngine {
            if (fail) error("boom")
            respond(body, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) {
        install(StackAuditKtor) { this.recorder = recorder }
    }

    @Test
    fun capturesRequestAndResponseWithoutConsumingBody() =
        runTest {
            val recorder = FakeRecorder()
            val response =
                client(recorder).post("https://api.test/items?q=1") {
                    header("Authorization", "Bearer abc")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"a"}""")
                }
            assertEquals("""{"ok":true}""", response.bodyAsText())
            val exchange = recorder.recorded.single()
            assertEquals("POST", exchange.method)
            assertEquals("https://api.test/items?q=1", exchange.url)
            assertEquals(201, exchange.status)
            assertEquals("""{"name":"a"}""", exchange.requestBody!!.decodeToString())
            assertEquals("""{"ok":true}""", exchange.responseBody!!.decodeToString())
            assertTrue(exchange.requestHeaders.any { it.name == "Authorization" && it.value == "Bearer abc" })
            assertTrue(exchange.responseContentType!!.startsWith("application/json"))
            assertEquals("ktor", exchange.source)
        }

    @Test
    fun recordsFailures() =
        runTest {
            val recorder = FakeRecorder()
            assertFailsWith<IllegalStateException> { client(recorder, fail = true).get("https://api.test/down") }
            val exchange = recorder.recorded.single()
            assertNull(exchange.status)
            assertEquals("IllegalStateException: boom", exchange.error)
        }

    @Test
    fun skipsWhenNotCapturing() =
        runTest {
            val recorder = FakeRecorder(isCapturing = false)
            assertEquals("""{"ok":true}""", client(recorder).get("https://api.test").bodyAsText())
            assertTrue(recorder.recorded.isEmpty())
        }

    @Test
    fun skipsBodiesAboveLimit() =
        runTest {
            val recorder = FakeRecorder(maxBodySize = 4)
            val big = "x".repeat(100)
            val response = client(recorder, body = big).get("https://api.test/big")
            assertEquals(big, response.bodyAsText())
            assertNull(recorder.recorded.single().responseBody)
            assertEquals(100L, recorder.recorded.single().responseBodySize)
        }
}
