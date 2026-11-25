package io.github.r1b2ns.stackaudit.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StackAuditKtorNoopTest {
    @Test
    fun installsWithoutChangingResponses() =
        runTest {
            val client =
                HttpClient(MockEngine { respond("ok") }) {
                    install(StackAuditKtor) { source = "custom" }
                }
            assertEquals("ok", client.get("https://a.test").bodyAsText())
        }
}
