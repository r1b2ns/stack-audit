package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import java.io.File
import java.security.MessageDigest
import java.util.Properties

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun createPlatformDriver(): SqlDriver {
    val dir = File(System.getProperty("user.home"), ".stackaudit").apply { mkdirs() }
    return JdbcSqliteDriver("jdbc:sqlite:${File(dir, "stackaudit.db").absolutePath}", Properties(), StackAuditDatabase.Schema)
}

internal actual fun platformCipher(): BlobCipher = PlainCipher

internal actual fun isDebuggableBuild(): Boolean = true

internal actual fun platformLog(message: String) {
    System.err.println(message)
}
