package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AuditEngineTest {
    private var clock = 1_700_000_000_000L
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), StackAuditDatabase.Schema)
    private val engines = mutableListOf<AuditEngine>()

    @AfterTest
    fun tearDown() =
        runBlocking<Unit> {
            engines.forEach { it.shutdown() }
            driver.close()
        }

    private fun engine(
        context: CoroutineContext = Dispatchers.IO,
        configure: StackAuditConfig.Builder.() -> Unit = {},
    ): AuditEngine = AuditEngine(StackAuditConfig.build(configure), driver, PlainCipher, context, { clock }).also { engines += it }

    private fun exchange(
        url: String = "https://api.test/login?token=abcdefghijklmnop&page=1",
        requestBody: String? = """{"user":"ana","password":"hunter2"}""",
        responseBody: ByteArray? = """{"ok":true,"access_token":"zzzzzzzzzzzz9999"}""".encodeToByteArray(),
        startedAt: Long = clock,
    ) = HttpExchange(
        source = "test",
        method = "POST",
        url = url,
        requestHeaders = listOf(Header("Authorization", "Bearer abcdefghijkl1234"), Header("Accept", "*/*")),
        requestBody = requestBody?.encodeToByteArray(),
        requestContentType = "application/json",
        status = 200,
        responseHeaders = listOf(Header("Set-Cookie", "sid=1")),
        responseBody = responseBody,
        responseContentType = "application/json",
        startedAt = startedAt,
        durationMs = 42,
    )

    private suspend fun AuditEngine.httpEvents(): List<AuditEvent> =
        summaries(Category.Http, null, 100).first().mapNotNull { event(it.id) }

    @Test
    fun masksSecretsBeforeWriting() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordHttp(exchange())
            engine.flush()
            val event = engine.httpEvents().single()
            val http = event.payload as HttpPayload
            assertEquals("https://api.test/login?token=••••mnop&page=1", http.url)
            assertEquals("Bearer ••••1234", http.requestHeaders.first { it.name == "Authorization" }.value)
            assertEquals("••••", http.responseHeaders.single().value)
            val request = engine.body(http.requestBody!!)!!.decodeToString()
            assertTrue("\"password\":\"••••\"" in request)
            assertFalse("hunter2" in request)
            val response = engine.body(http.responseBody!!)!!.decodeToString()
            assertTrue("••••9999" in response)
            assertEquals("POST api.test/login 200", event.title)
        }

    @Test
    fun statsCountEventsBytesAndOldest() =
        runBlocking<Unit> {
            val engine = engine()
            engine.flush()
            val empty = engine.stats()
            val first = clock
            engine.recordHttp(exchange(startedAt = first))
            clock += 5_000
            engine.recordMeta(MetaKind.Exported, "JSON export of 2 events", 2)
            engine.flush()
            val stats = engine.stats()
            assertEquals(empty.events + 2, stats.events)
            assertTrue(stats.bytes > empty.bytes)
            assertEquals(empty.oldest ?: first, stats.oldest)
            val exported = engine.summaries(Category.Audit, "JSON export", 10).first().single()
            assertEquals("JSON export of 2 events", exported.title)
        }

    @Test
    fun summaryCarriesBodySizes() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordHttp(exchange(requestBody = null))
            engine.recordHttp(exchange())
            engine.flush()
            val (withBodies, withoutRequest) = engine.summaries(Category.Http, null, 10).first()
            val http = engine.event(withBodies.id)!!.payload as HttpPayload
            assertEquals(http.requestBody!!.size, withBodies.requestSize)
            assertEquals(http.responseBody!!.size, withBodies.responseSize)
            assertTrue(withBodies.requestSize!! > 0)
            assertNull(withoutRequest.requestSize)
            assertEquals(withBodies.responseSize, withoutRequest.responseSize)
        }

    @Test
    fun showModeKeepsSecretsButExportMasksByDefault() =
        runBlocking<Unit> {
            val engine = engine { secrets = Secrets.Show }
            engine.recordHttp(exchange())
            engine.flush()
            val http = engine.httpEvents().single().payload as HttpPayload
            assertEquals("Bearer abcdefghijkl1234", http.requestHeaders.first().value)
            assertTrue("hunter2" in engine.body(http.requestBody!!)!!.decodeToString())

            val masked = engine.exportHar(includeSecrets = false)
            assertFalse("hunter2" in masked)
            assertFalse("abcdefghijkl1234" in masked)
            val revealed = engine.exportHar(includeSecrets = true)
            assertTrue("hunter2" in revealed)
            assertFalse("abcdefghijkl1234" in engine.exportJsonl(includeSecrets = false))
        }

    @Test
    fun harExportFollowsSpec() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordHttp(exchange())
            engine.flush()
            val har = Json.parseToJsonElement(engine.exportHar(false)).jsonObject["log"]!!.jsonObject
            assertEquals("1.2", har["version"]!!.jsonPrimitive.content)
            val entry = har["entries"]!!.jsonArray.single().jsonObject
            val request = entry["request"]!!.jsonObject
            assertEquals("POST", request["method"]!!.jsonPrimitive.content)
            assertEquals("page", request["queryString"]!!.jsonArray[1].jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(request["postData"]!!.jsonObject["text"]!!.jsonPrimitive.content.contains("ana"))
            val response = entry["response"]!!.jsonObject
            assertEquals(200, response["status"]!!.jsonPrimitive.content.toInt())
            assertEquals("2023-11-14T22:13:20Z", entry["startedDateTime"]!!.jsonPrimitive.content)
        }

    @Test
    fun bodiesAboveLimitKeepOnlySize() =
        runBlocking<Unit> {
            val engine = engine { maxBodySize = 8 }
            engine.recordHttp(exchange())
            engine.flush()
            val http = engine.httpEvents().single().payload as HttpPayload
            assertFalse(http.requestBody!!.stored)
            assertNull(http.requestBody!!.hash)
            assertNull(engine.body(http.requestBody!!))
        }

    @Test
    fun identicalBodiesAreStoredOnce() =
        runBlocking<Unit> {
            val engine = engine()
            repeat(3) { engine.recordHttp(exchange()) }
            engine.flush()
            val blobs = driver.executeQuery(null, "SELECT COUNT(*) FROM audit_blob", { it.next(); app.cash.sqldelight.db.QueryResult.Value(it.getLong(0)) }, 0).value
            assertEquals(2L, blobs)
        }

    @Test
    fun hashChainDetectsTampering() =
        runBlocking<Unit> {
            val engine = engine()
            repeat(3) { engine.recordHttp(exchange()) }
            engine.flush()
            val report = engine.verify()
            assertTrue(report.valid)
            assertEquals(3, report.checked)
            driver.execute(null, "UPDATE audit_event SET title = 'forged' WHERE seq = 3", 0)
            val broken = engine.verify()
            assertFalse(broken.valid)
            assertNotNull(broken.brokenAt)
        }

    @Test
    fun retentionPurgesOldEventsAndKeepsChainValid() =
        runBlocking<Unit> {
            val engine = engine { retention = 7.days }
            engine.recordHttp(exchange(startedAt = clock - 10.days.inWholeMilliseconds))
            engine.recordHttp(exchange(startedAt = clock))
            engine.flush()
            engine.purgeNow()
            val titles = engine.summaries(null, null, 100).first()
            assertEquals(1, titles.count { it.category == Category.Http })
            val purged = titles.first { it.category == Category.Audit }
            assertEquals("1 events purged", purged.title)
            assertTrue(engine.verify().valid)
        }

    @Test
    fun storageLimitPurgesOldestEvents() =
        runBlocking<Unit> {
            val engine = engine { maxStorage = 4_000 }
            repeat(40) { engine.recordHttp(exchange(url = "https://api.test/item/$it")) }
            engine.flush()
            engine.purgeNow()
            val remaining = engine.summaries(Category.Http, null, 100).first()
            assertTrue(remaining.size in 1 until 40)
            assertTrue(remaining.none { it.title.contains("/item/0 ") })
            assertTrue(engine.verify().valid)
        }

    @Test
    fun fullQueueRecordsDroppedEvents() =
        runBlocking<Unit> {
            val executor = Executors.newSingleThreadExecutor()
            val gate = CountDownLatch(1)
            executor.submit { gate.await() }
            val engine = engine(context = executor.asCoroutineDispatcher()) { queueCapacity = 2 }
            repeat(5) { engine.recordHttp(exchange()) }
            gate.countDown()
            engine.flush()
            val dropped = engine.summaries(Category.Audit, null, 100).first().map { it.title }
            assertTrue(dropped.any { it.endsWith("events dropped") }, dropped.toString())
            engine.shutdown()
            engines -= engine
            executor.shutdown()
        }

    @Test
    fun disabledCategoryIsNotRecorded() =
        runBlocking<Unit> {
            val engine = engine { categories = setOf(Category.Audit) }
            engine.recordHttp(exchange())
            engine.flush()
            assertTrue(engine.summaries(Category.Http, null, 100).first().isEmpty())
        }

    @Test
    fun clearRemovesEverythingAndLeavesAMarker() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordHttp(exchange())
            engine.flush()
            engine.clear()
            val rows = engine.summaries(null, null, 100).first()
            assertEquals(listOf("1 events cleared"), rows.map { it.title }.filter { "cleared" in it })
            assertTrue(rows.none { it.category == Category.Http })
        }

    @Test
    fun searchFiltersByTitle() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordHttp(exchange(url = "https://api.test/users"))
            engine.recordHttp(exchange(url = "https://api.test/orders"))
            engine.flush()
            assertEquals(1, engine.summaries(Category.Http, "orders", 100).first().size)
        }

    private fun frame(
        kind: WebSocketKind,
        text: String? = null,
        binary: ByteArray? = null,
        timestamp: Long = clock,
    ) = WebSocketFrame(
        source = "test",
        connectionId = "c1",
        url = "wss://ws.test/chat?token=abcdefghijklmnop",
        kind = kind,
        timestamp = timestamp,
        data = text?.encodeToByteArray() ?: binary,
        isText = binary == null,
        code = if (kind == WebSocketKind.Closed) 1000 else null,
    )

    @Test
    fun recordsWebSocketFramesWithMasking() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordWebSocket(frame(WebSocketKind.Open))
            engine.recordWebSocket(frame(WebSocketKind.Sent, text = """{"password":"hunter2","msg":"hi"}"""))
            engine.recordWebSocket(frame(WebSocketKind.Received, binary = byteArrayOf(1, 2, 3)))
            engine.recordWebSocket(frame(WebSocketKind.Closed))
            engine.flush()
            val rows = engine.summaries(Category.WebSocket, null, 100).first().reversed()
            assertEquals(
                listOf("WS open ws.test/chat", "WS ↑ ws.test/chat (33 B)", "WS ↓ ws.test/chat (3 B)", "WS closed ws.test/chat 1000"),
                rows.map { it.title },
            )
            val sent = engine.event(rows[1].id)!!
            assertEquals("c1", sent.traceId)
            val payload = sent.payload as WebSocketPayload
            assertEquals("wss://ws.test/chat?token=••••mnop", payload.url)
            val body = engine.body(payload.body!!)!!.decodeToString()
            assertTrue("\"password\":\"••••\"" in body)
            assertTrue("hi" in body)
            val binary = (engine.event(rows[2].id)!!.payload as WebSocketPayload).body!!
            assertFalse(binary.text)
            assertEquals(listOf<Byte>(1, 2, 3), engine.body(binary)!!.toList())
        }

    @Test
    fun webSocketCategoryCanBeDisabled() =
        runBlocking<Unit> {
            val engine = engine { categories = setOf(Category.Http) }
            engine.recordWebSocket(frame(WebSocketKind.Open))
            engine.flush()
            assertTrue(engine.summaries(Category.WebSocket, null, 100).first().isEmpty())
        }

    @Test
    fun purgeKeepsBlobsStillReferenced() =
        runBlocking<Unit> {
            val engine = engine { retention = 7.days }
            engine.recordWebSocket(frame(WebSocketKind.Sent, text = "same", timestamp = clock - 10.days.inWholeMilliseconds))
            engine.recordWebSocket(frame(WebSocketKind.Sent, text = "same"))
            engine.flush()
            engine.purgeNow()
            val remaining = engine.summaries(Category.WebSocket, null, 100).first().single()
            val ref = (engine.event(remaining.id)!!.payload as WebSocketPayload).body!!
            assertEquals("same", engine.body(ref)!!.decodeToString())
        }

    @Test
    fun recordsStorageChangesWithMasking() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordStorage(StorageChange("shared_preferences", "prefs", "token", StorageOperation.Write, clock, "old-value-123456", "new-value-abcdef"))
            engine.recordStorage(StorageChange("shared_preferences", "prefs", "theme", StorageOperation.Write, clock, null, "dark"))
            engine.recordStorage(StorageChange("shared_preferences", "prefs", null, StorageOperation.Clear, clock))
            engine.flush()
            val rows = engine.summaries(Category.Storage, null, 100).first().reversed()
            assertEquals(listOf("prefs set token", "prefs set theme", "prefs clear"), rows.map { it.title })
            val token = engine.event(rows[0].id)!!.payload as StoragePayload
            assertEquals("••••3456", token.oldValue)
            assertEquals("••••cdef", token.newValue)
            assertEquals("dark", (engine.event(rows[1].id)!!.payload as StoragePayload).newValue)
        }

    @Test
    fun recordsDatabaseStatementsWithMaskedArguments() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordDatabase(
                DatabaseStatement("sqldelight", "app.db", "UPDATE user SET password = ? WHERE id = ?", clock, listOf("hunter2", 7L), 3, 1),
            )
            engine.recordDatabase(DatabaseStatement("sqldelight", "app.db", "SELECT * FROM blobs WHERE data = ?", clock, listOf(byteArrayOf(1, 2))))
            engine.flush()
            val rows = engine.summaries(Category.Database, null, 100).first().reversed()
            assertEquals("app.db: UPDATE user SET password = ? WHERE id = ?", rows[0].title)
            val first = engine.event(rows[0].id)!!.payload as DatabasePayload
            assertEquals(listOf("••••", "7"), first.arguments)
            assertEquals(1L, first.rowsAffected)
            assertEquals(3L, rows[0].durationMs)
            assertEquals(listOf("<2 bytes>"), (engine.event(rows[1].id)!!.payload as DatabasePayload).arguments)
        }

    @Test
    fun masksSecretLiteralsInRawSql() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordDatabase(DatabaseStatement("sqlite3", "app.db", "UPDATE user SET password = 'hunter2' WHERE id = 7", clock))
            engine.flush()
            val row = engine.summaries(Category.Database, null, 10).first().single()
            assertEquals("app.db: UPDATE user SET password = '••••' WHERE id = 7", row.title)
            assertEquals("UPDATE user SET password = '••••' WHERE id = 7", (engine.event(row.id)!!.payload as DatabasePayload).sql)
        }

    @Test
    fun showAfterHandshakeKeepsTheRawSqlEncrypted() =
        runBlocking<Unit> {
            val engine =
                engine {
                    access = Access.Handshake("a".repeat(64))
                    secrets = Secrets.ShowAfterHandshake()
                }
            val sql = "INSERT INTO session (id, token) VALUES (1, 'abc')"
            engine.recordDatabase(DatabaseStatement("sqlite3", "app.db", sql, clock))
            engine.flush()
            val row = engine.summaries(Category.Database, null, 10).first().single()
            assertEquals("INSERT INTO session (id, token) VALUES (1, '••••')", (engine.event(row.id)!!.payload as DatabasePayload).sql)
            assertEquals(sql, (engine.rawPayload(row.id) as DatabasePayload).sql)
        }

    @Test
    fun showModeKeepsRawSql() =
        runBlocking<Unit> {
            val engine = engine { secrets = Secrets.Show }
            engine.recordDatabase(DatabaseStatement("sqlite3", "app.db", "SELECT * FROM u WHERE token = 'abc'", clock))
            engine.flush()
            val row = engine.summaries(Category.Database, null, 10).first().single()
            assertEquals("SELECT * FROM u WHERE token = 'abc'", (engine.event(row.id)!!.payload as DatabasePayload).sql)
        }

    @Test
    fun showModeKeepsStorageValues() =
        runBlocking<Unit> {
            val engine = engine { secrets = Secrets.Show }
            engine.recordStorage(StorageChange("user_defaults", "standard", "token", StorageOperation.Write, clock, null, "abc"))
            engine.flush()
            val row = engine.summaries(Category.Storage, null, 10).first().single()
            assertEquals("abc", (engine.event(row.id)!!.payload as StoragePayload).newValue)
        }

    @Test
    fun recordsAnalyticsAndFlags() =
        runBlocking<Unit> {
            val engine = engine()
            engine.recordAnalytics(
                AnalyticsCall("firebase", "login", mapOf("method" to "email", "password" to "hunter2"), clock, ValidationStatus.UnknownEvent, listOf("x")),
            )
            engine.recordFlag(
                FlagPayload("checkout", "remote", FlagValue.Bool(false), FlagValue.Bool(true), null, FlagValue.Bool(true), FlagSource.Remote),
                clock,
            )
            engine.flush()
            val analytics = engine.summaries(Category.Analytics, null, 10).first().single()
            assertEquals("firebase: login (not in plan)", analytics.title)
            val payload = engine.event(analytics.id)!!.payload as AnalyticsPayload
            assertEquals(mapOf("method" to "email", "password" to "••••"), payload.params)
            assertEquals("flag checkout = true (remote)", engine.summaries(Category.Flag, null, 10).first().single().title)
        }

    @Test
    fun overrideStorePersistsInTheDatabase() {
        val engine = engine()
        engine.overrideStore.save("a", FlagValue.Number(2.5))
        engine.overrideStore.save("b", FlagValue.Text("x"))
        engine.overrideStore.save("b", null)
        assertEquals(mapOf<String, FlagValue>("a" to FlagValue.Number(2.5)), engine.overrideStore.load())
    }
}
