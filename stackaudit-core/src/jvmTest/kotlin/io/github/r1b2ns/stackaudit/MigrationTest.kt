package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    @Test
    fun versionOneDatabaseGainsBlobReferences() {
        val file = File.createTempFile("stackaudit", ".db").apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath}"
        JdbcSqliteDriver(url, Properties()).use { old ->
            listOf(
                "CREATE TABLE audit_event (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, timestamp INTEGER NOT NULL, duration INTEGER, category TEXT NOT NULL, source TEXT NOT NULL, trace_id TEXT, title TEXT NOT NULL, payload TEXT NOT NULL, size INTEGER NOT NULL, prev_hash TEXT, hash TEXT NOT NULL)",
                "CREATE TABLE audit_http (event_seq INTEGER NOT NULL PRIMARY KEY, method TEXT NOT NULL, host TEXT NOT NULL, status INTEGER, request_hash TEXT, response_hash TEXT)",
                "CREATE TABLE audit_blob (hash TEXT NOT NULL PRIMARY KEY, data BLOB NOT NULL, size INTEGER NOT NULL)",
                "INSERT INTO audit_http VALUES (1, 'GET', 'a.test', 200, 'req', 'res')",
                "INSERT INTO audit_http VALUES (2, 'GET', 'a.test', 200, NULL, 'res2')",
                "PRAGMA user_version = 1",
            ).forEach { old.execute(null, it, 0) }
        }
        JdbcSqliteDriver(url, Properties(), StackAuditDatabase.Schema).use { driver ->
            val refs =
                driver.executeQuery(null, "SELECT COUNT(*) FROM audit_ref", { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                }, 0).value
            assertEquals(3L, refs)
            assertEquals(6L, StackAuditDatabase.Schema.version)
        }
    }

    @Test
    fun versionFourDatabaseGainsBodySizes() {
        val file = File.createTempFile("stackaudit", ".db").apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath}"
        JdbcSqliteDriver(url, Properties()).use { old ->
            listOf(
                "CREATE TABLE audit_http (event_seq INTEGER NOT NULL PRIMARY KEY, method TEXT NOT NULL, host TEXT NOT NULL, status INTEGER, request_hash TEXT, response_hash TEXT)",
                "INSERT INTO audit_http VALUES (1, 'GET', 'a.test', 200, NULL, 'res')",
                "PRAGMA user_version = 4",
            ).forEach { old.execute(null, it, 0) }
        }
        JdbcSqliteDriver(url, Properties(), StackAuditDatabase.Schema).use { driver ->
            driver.execute(null, "INSERT INTO audit_http VALUES (2, 'POST', 'a.test', 201, NULL, NULL, 12, 34)", 0)
            val sizes =
                driver.executeQuery(null, "SELECT event_seq, request_size, response_size FROM audit_http ORDER BY event_seq", { cursor ->
                    val rows = mutableListOf<Triple<Long, Long?, Long?>>()
                    while (cursor.next().value) rows += Triple(cursor.getLong(0)!!, cursor.getLong(1), cursor.getLong(2))
                    QueryResult.Value(rows)
                }, 0).value
            assertEquals(listOf(Triple(1L, null, null), Triple(2L, 12L, 34L)), sizes)
        }
    }
}
