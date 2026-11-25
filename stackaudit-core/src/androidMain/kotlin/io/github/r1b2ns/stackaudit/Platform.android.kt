package io.github.r1b2ns.stackaudit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import java.io.File
import java.security.MessageDigest

public object StackAuditAndroid {
    @Volatile
    internal var context: Context? = null

    public fun attach(context: Context) {
        this.context = context.applicationContext
    }

    internal fun require(): Context =
        checkNotNull(context) {
            "StackAudit: no Context. Keep androidx.startup enabled or call StackAuditAndroid.attach(context) before start()."
        }
}

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun createPlatformDriver(): SqlDriver {
    val context = StackAuditAndroid.require()
    val path = File(context.noBackupFilesDir, "stackaudit.db").absolutePath
    return AndroidSqliteDriver(StackAuditDatabase.Schema, context, path)
}

internal actual fun platformCipher(): BlobCipher = KeystoreCipher()

internal actual fun isDebuggableBuild(): Boolean {
    val context = StackAuditAndroid.context ?: return false
    return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

internal actual fun platformLog(message: String) {
    Log.w("StackAudit", message)
}

internal actual fun platformAppId(): String = StackAuditAndroid.context?.packageName ?: "android"
