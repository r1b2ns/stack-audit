package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackAuditTest {
    private val now = 1_700_000_000_000L
    private val signer = TestSigner()

    private fun start(configure: StackAuditConfig.Builder.() -> Unit = {}) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), StackAuditDatabase.Schema)
        StackAudit.start(StackAuditConfig.build(configure), driver, PlainCipher, Dispatchers.IO) { now }
    }

    @AfterTest
    fun tearDown() = runBlocking { StackAudit.stop() }

    @Test
    fun startRecordsStartedMarkerAndIgnoresSecondStart() =
        runBlocking<Unit> {
            start()
            start { access = Access.Hidden }
            StackAudit.flush()
            assertEquals(Access.Open, StackAudit.config?.access)
            assertEquals(listOf("StackAudit started"), StackAudit.events().first().map { it.title })
        }

    @Test
    fun showRespectsAccessMode() =
        runBlocking<Unit> {
            start { access = Access.Hidden }
            var presented = false
            StackAudit.registerViewer { presented = true }
            assertFalse(StackAudit.show())
            StackAudit.stop()
            start()
            assertTrue(StackAudit.show())
            assertTrue(presented)
        }

    @Test
    fun recordIsIgnoredBeforeStart() =
        runBlocking<Unit> {
            assertFalse(StackAudit.isCapturing)
            StackAudit.record(
                HttpExchange("t", "GET", "https://a.test", emptyList(), null, null, 200, emptyList(), null, null, 0, 0),
            )
            assertTrue(StackAudit.events().first().isEmpty())
        }

    @Test
    fun handshakeOpensTheViewer() =
        runBlocking<Unit> {
            start { access = Access.Handshake(signer.publicKey) }
            var presented = false
            StackAudit.registerViewer { presented = true }
            assertFalse(StackAudit.show())
            assertFalse(StackAudit.handleLink("myapp://stackaudit/open?token=bad"))
            val token = signer.token(scope = listOf("read"), iat = now, exp = now + 60_000)
            assertTrue(StackAudit.handleLink("myapp://stackaudit/open?token=$token"))
            assertTrue(presented)
            assertFalse(StackAudit.allows(Scope.Flags))
            StackAudit.endSession()
            assertFalse(StackAudit.allows(Scope.Read))
            StackAudit.flush()
            val titles = StackAudit.events(Category.Audit).first().map { it.title }
            assertTrue(titles.any { it.startsWith("handshake rejected") }, titles.toString())
            assertTrue("handshake accepted: read" in titles, titles.toString())
        }

    @Test
    fun invalidPublicKeyFailsAtStart() {
        assertFailsWith<IllegalArgumentException> { start { access = Access.Handshake("nope") } }
    }

    @Test
    fun showAfterHandshakeRevealsOnlyWithSecretsScope() =
        runBlocking<Unit> {
            start {
                access = Access.Handshake(signer.publicKey)
                secrets = Secrets.ShowAfterHandshake()
            }
            StackAudit.record(
                HttpExchange(
                    "t", "POST", "https://a.test/login?token=abcdefghijklmnop", listOf(Header("Authorization", "Bearer abcdefghijkl1234")),
                    """{"password":"hunter2"}""".encodeToByteArray(), "application/json", 200, emptyList(), null, null, now, 1,
                ),
            )
            StackAudit.flush()
            val id = StackAudit.events(Category.Http).first().single().id
            val masked = StackAudit.event(id, revealSecrets = true)!!.payload as HttpPayload
            assertEquals("Bearer ••••1234", masked.requestHeaders.single().value)

            StackAudit.handshake(signer.token(scope = listOf("read", "read_secrets"), iat = now, exp = now + 60_000))
            val raw = StackAudit.event(id, revealSecrets = true)!!.payload as HttpPayload
            assertEquals("Bearer abcdefghijkl1234", raw.requestHeaders.single().value)
            assertEquals("https://a.test/login?token=abcdefghijklmnop", raw.url)
            assertEquals("""{"password":"hunter2"}""", StackAudit.body(raw.requestBody!!)!!.decodeToString())
            val stillMasked = StackAudit.event(id)!!.payload as HttpPayload
            assertEquals("Bearer ••••1234", stillMasked.requestHeaders.single().value)
        }
}
