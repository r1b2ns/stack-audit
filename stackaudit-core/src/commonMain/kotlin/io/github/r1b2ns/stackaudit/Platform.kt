package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.SqlDriver

internal expect fun createPlatformDriver(): SqlDriver

internal expect fun platformCipher(): BlobCipher

internal expect fun isDebuggableBuild(): Boolean

internal expect fun platformLog(message: String)
