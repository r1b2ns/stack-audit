package io.github.r1b2ns.stackaudit.sqldelight

import app.cash.sqldelight.db.SqlDriver
import io.github.r1b2ns.stackaudit.DatabaseRecorder
import io.github.r1b2ns.stackaudit.StackAudit

public fun SqlDriver.audited(
    database: String,
    recorder: DatabaseRecorder = StackAudit,
): SqlDriver = this.also { database.hashCode() + recorder.hashCode() }
