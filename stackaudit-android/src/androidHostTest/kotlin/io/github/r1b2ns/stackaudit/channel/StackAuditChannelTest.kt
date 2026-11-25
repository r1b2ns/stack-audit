package io.github.r1b2ns.stackaudit.channel

import io.github.r1b2ns.stackaudit.Access
import io.github.r1b2ns.stackaudit.FlagValue
import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class StackAuditChannelTest {
    private class Recorder : HttpRecorder, StorageRecorder {
        val exchanges = mutableListOf<HttpExchange>()
        val changes = mutableListOf<StorageChange>()
        override val isCapturing = true
        override val maxBodySize = 2048L
        override val isCapturingStorage = true

        override fun record(exchange: HttpExchange) {
            exchanges += exchange
        }

        override fun record(change: StorageChange) {
            changes += change
        }
    }

    private val recorder = Recorder()
    private val channel = StackAuditChannel(recorder, recorder) { 42L }

    @Test
    fun mapsAnHttpExchangeFromDart() {
        channel.handle(
            "recordHttp",
            mapOf(
                "method" to "POST",
                "url" to "https://api.example.com/login",
                "requestHeaders" to mapOf("content-type" to "application/json"),
                "requestBody" to "{}".encodeToByteArray(),
                "status" to 201,
                "responseHeaders" to mapOf("Content-Type" to "text/plain"),
                "responseBody" to "ok".encodeToByteArray(),
                "startedAt" to 1000,
                "durationMs" to 12,
            ),
        )
        val exchange = recorder.exchanges.single()
        assertEquals("flutter", exchange.source)
        assertEquals(201, exchange.status)
        assertEquals("application/json", exchange.requestContentType)
        assertEquals("text/plain", exchange.responseContentType)
        assertEquals(1000L, exchange.startedAt)
        assertEquals("ok", exchange.responseBody?.decodeToString())
    }

    @Test
    fun mapsAStorageChange() {
        channel.handle("recordStorage", mapOf("store" to "shared_preferences", "key" to "token", "operation" to "write", "newValue" to "abc"))
        val change = recorder.changes.single()
        assertEquals(StorageOperation.Write, change.operation)
        assertEquals(42L, change.timestamp)
        assertEquals("abc", change.newValue)
        assertFailsWith<IllegalArgumentException> { channel.handle("recordStorage", mapOf("store" to "s", "operation" to "nope")) }
    }

    @Test
    fun reportsCaptureState() {
        assertEquals(true, channel.handle("isCapturing", emptyMap()))
        assertEquals(2048L, channel.handle("maxBodySize", emptyMap()))
    }

    @Test
    fun buildsConfigFromDartOptions() {
        val config =
            StackAuditChannel.config(
                mapOf(
                    "secrets" to "showAfterHandshake",
                    "access" to "handshake",
                    "publicKey" to "a".repeat(64),
                    "sessionTtlSeconds" to 600,
                    "appId" to "com.example",
                    "maxBodySize" to 10,
                ),
            )
        assertIs<Secrets.ShowAfterHandshake>(config.secrets)
        assertEquals(Access.Handshake("a".repeat(64), 10.minutes, "com.example"), config.access)
        assertEquals(10L, config.maxBodySize)
        assertIs<Secrets.Mask>(StackAuditChannel.config(emptyMap()).secrets)
        assertFailsWith<IllegalArgumentException> { StackAuditChannel.config(mapOf("access" to "handshake")) }
        assertFailsWith<IllegalArgumentException> { StackAuditChannel.config(mapOf("secrets" to "loud")) }
    }

    @Test
    fun convertsFlagValues() {
        assertEquals(FlagValue.Bool(true), StackAuditChannel.flagValue("bool", true))
        assertEquals(FlagValue.Number(2.0), StackAuditChannel.flagValue("number", 2))
        assertEquals(FlagValue.Text("x"), StackAuditChannel.flagValue("string", "x"))
        assertNull(StackAuditChannel.flagValue("bool", null))
        assertNull(StackAuditChannel.flagValue("bool", "yes"))
    }

    @Test
    fun rejectsUnknownMethodsAndMissingArguments() {
        assertFailsWith<UnsupportedOperationException> { channel.handle("explode", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { channel.handle("recordHttp", mapOf("method" to "GET")) }
    }
}
