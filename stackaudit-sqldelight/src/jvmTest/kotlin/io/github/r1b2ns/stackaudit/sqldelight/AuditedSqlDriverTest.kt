package io.github.r1b2ns.stackaudit.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.DatabaseStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuditedSqlDriverTest {
    private class Recorder(
        override val isCapturingDatabase: Boolean = true,
    ) : DatabaseRecorder {
        val statements = mutableListOf<DatabaseStatement>()

        override fun record(statement: DatabaseStatement) {
            statements += statement
        }
    }

    @Test
    fun recordsStatementsWithArgumentsAndRows() {
        val recorder = Recorder()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).audited("app.db", recorder)
        driver.execute(null, "CREATE TABLE user(id INTEGER, name TEXT)", 0)
        driver.execute(null, "INSERT INTO user(id, name) VALUES (?, ?)", 2) {
            bindLong(0, 1)
            bindString(1, "ana")
        }
        val name =
            driver.executeQuery(null, "SELECT name FROM user WHERE id = ?", { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            }, 1) { bindLong(0, 1) }.value
        assertEquals("ana", name)
        assertEquals(3, recorder.statements.size)
        val insert = recorder.statements[1]
        assertEquals(listOf<Any?>(1L, "ana"), insert.arguments)
        assertEquals(1L, insert.rowsAffected)
        assertEquals("sqldelight", insert.source)
        assertEquals("app.db", insert.database)
        assertEquals(listOf<Any?>(1L), recorder.statements[2].arguments)
    }

    @Test
    fun recordsErrorsAndRethrows() {
        val recorder = Recorder()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).audited("app.db", recorder)
        assertFailsWith<Exception> { driver.execute(null, "INSERT INTO missing VALUES (1)", 0) }
        assertNotNull(recorder.statements.single().error)
    }

    @Test
    fun skipsWhenNotCapturingAndIsIdempotent() {
        val recorder = Recorder(isCapturingDatabase = false)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).audited("app.db", recorder)
        driver.execute(null, "CREATE TABLE t(a INTEGER)", 0)
        assertTrue(recorder.statements.isEmpty())
        assertSame(driver, driver.audited("app.db", recorder))
    }
}
