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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackAuditTest {
    private fun start(configure: StackAuditConfig.Builder.() -> Unit = {}) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), StackAuditDatabase.Schema)
        StackAudit.start(StackAuditConfig.build(configure), driver, PlainCipher, Dispatchers.IO) { 0L }
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
}
