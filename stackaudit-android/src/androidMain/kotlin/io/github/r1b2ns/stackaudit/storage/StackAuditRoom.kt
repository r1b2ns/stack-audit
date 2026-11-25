package io.github.r1b2ns.stackaudit.storage

import androidx.room.RoomDatabase
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.DatabaseStatement
import io.github.r1b2ns.stackaudit.StackAudit
import java.util.concurrent.Executor
import java.util.concurrent.Executors

public fun stackAuditQueryCallback(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
): RoomDatabase.QueryCallback =
    RoomDatabase.QueryCallback { sql, arguments ->
        if (recorder.isCapturingDatabase) {
            recorder.record(DatabaseStatement("room", database, sql, System.currentTimeMillis(), arguments.toList()))
        }
    }

public fun <T : RoomDatabase> RoomDatabase.Builder<T>.audited(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
    executor: Executor = Executors.newSingleThreadExecutor(),
): RoomDatabase.Builder<T> = setQueryCallback(stackAuditQueryCallback(database, recorder), executor)
