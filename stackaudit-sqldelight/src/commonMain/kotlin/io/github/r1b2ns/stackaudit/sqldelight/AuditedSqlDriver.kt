package io.github.r1b2ns.stackaudit.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.DatabaseStatement
import io.github.r1b2ns.stackaudit.StackAudit
import kotlin.time.Clock
import kotlin.time.TimeSource

public fun SqlDriver.audited(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
): SqlDriver = if (this is AuditedSqlDriver) this else AuditedSqlDriver(this, database, recorder)

internal class AuditedSqlDriver(
    private val delegate: SqlDriver,
    private val database: String,
    private val recorder: DatabaseRecorder,
) : SqlDriver by delegate {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> =
        track(sql, parameters, binders, rows = { (it as? QueryResult.Value<Long>)?.value }) {
            delegate.execute(identifier, sql, parameters, binders)
        }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> =
        track(sql, parameters, binders, rows = { null }) {
            delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

    private inline fun <T> track(
        sql: String,
        parameters: Int,
        noinline binders: (SqlPreparedStatement.() -> Unit)?,
        rows: (T) -> Long?,
        block: () -> T,
    ): T {
        if (!recorder.isCapturingDatabase) return block()
        val arguments = ArgumentCollector(parameters).also { collector -> binders?.invoke(collector) }.values
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val started = TimeSource.Monotonic.markNow()
        val result =
            try {
                block()
            } catch (error: Throwable) {
                recorder.record(
                    DatabaseStatement(
                        source = SOURCE,
                        database = database,
                        sql = sql,
                        timestamp = timestamp,
                        arguments = arguments,
                        durationMs = started.elapsedNow().inWholeMilliseconds,
                        error = (error::class.simpleName ?: "Error") + (error.message?.let { ": $it" } ?: ""),
                    ),
                )
                throw error
            }
        recorder.record(
            DatabaseStatement(
                source = SOURCE,
                database = database,
                sql = sql,
                timestamp = timestamp,
                arguments = arguments,
                durationMs = started.elapsedNow().inWholeMilliseconds,
                rowsAffected = rows(result),
            ),
        )
        return result
    }

    private class ArgumentCollector(
        parameters: Int,
    ) : SqlPreparedStatement {
        val values = MutableList<Any?>(parameters) { null }

        private fun set(
            index: Int,
            value: Any?,
        ) {
            if (index in values.indices) values[index] = value
        }

        override fun bindBytes(
            index: Int,
            bytes: ByteArray?,
        ) = set(index, bytes)

        override fun bindLong(
            index: Int,
            long: Long?,
        ) = set(index, long)

        override fun bindDouble(
            index: Int,
            double: Double?,
        ) = set(index, double)

        override fun bindString(
            index: Int,
            string: String?,
        ) = set(index, string)

        override fun bindBoolean(
            index: Int,
            boolean: Boolean?,
        ) = set(index, boolean)
    }

    private companion object {
        const val SOURCE = "sqldelight"
    }
}
