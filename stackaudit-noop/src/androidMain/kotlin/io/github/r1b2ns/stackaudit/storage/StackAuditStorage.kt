package io.github.r1b2ns.stackaudit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.room.RoomDatabase
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StorageRecorder
import java.util.concurrent.Executor

public fun SharedPreferences.audited(
    name: String,
    recorder: StorageRecorder = StackAudit,
): SharedPreferences = this.also { name.hashCode() + recorder.hashCode() }

public fun Context.auditedSharedPreferences(
    name: String,
    mode: Int = Context.MODE_PRIVATE,
    recorder: StorageRecorder = StackAudit,
): SharedPreferences = getSharedPreferences(name, mode).audited(name, recorder)

public fun stackAuditQueryCallback(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
): RoomDatabase.QueryCallback = RoomDatabase.QueryCallback { _, _ -> database.hashCode() + recorder.hashCode() }

public fun <T : RoomDatabase> RoomDatabase.Builder<T>.audited(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
    executor: Executor? = null,
): RoomDatabase.Builder<T> = this.also { database.hashCode() + recorder.hashCode() + executor.hashCode() }
