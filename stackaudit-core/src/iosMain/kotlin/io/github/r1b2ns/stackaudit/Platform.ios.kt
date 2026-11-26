package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseFileContext
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { source ->
        out.usePinned { digest -> CC_SHA256(source.addressOf(0), bytes.size.convert(), digest.addressOf(0)) }
    }
    return out.asByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createPlatformDriver(): SqlDriver {
    val driver = NativeSqliteDriver(StackAuditDatabase.Schema, DATABASE)
    val path = DatabaseFileContext.databasePath(DATABASE, null)
    NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
    return driver
}

public object StackAuditIos {
    public var cipher: BlobCipher? = null
}

internal actual fun platformCipher(): BlobCipher = StackAuditIos.cipher ?: PlainCipher

@OptIn(ExperimentalNativeApi::class)
internal actual fun isDebuggableBuild(): Boolean = Platform.isDebugBinary

internal actual fun platformLog(message: String) {
    NSLog(message.replace("%", "%%"))
}

private const val DATABASE = "stackaudit.db"

internal actual fun platformAppId(): String = platform.Foundation.NSBundle.mainBundle.bundleIdentifier ?: "ios"
