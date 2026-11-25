package io.github.r1b2ns.stackaudit.apple

import io.github.r1b2ns.stackaudit.Access
import io.github.r1b2ns.stackaudit.BlobCipher
import io.github.r1b2ns.stackaudit.StackAuditIos
import io.github.r1b2ns.stackaudit.DatabaseStatement
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.WebSocketFrame
import io.github.r1b2ns.stackaudit.WebSocketKind
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StackAuditConfig
import io.github.r1b2ns.stackaudit.ui.StackAuditUi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.time.Duration.Companion.seconds

public enum class SecretsMode { Mask, Show, ShowAfterHandshake }

public enum class AccessMode { Open, Handshake, Hidden }

public class BridgeOptions {
    public var secrets: SecretsMode = SecretsMode.Mask
    public var extraHeaders: List<String> = emptyList()
    public var extraFields: List<String> = emptyList()
    public var allowed: List<String> = emptyList()
    public var access: AccessMode = AccessMode.Open
    public var publicKey: String? = null
    public var sessionTtlSeconds: Long = 1800
    public var retentionSeconds: Long = 7 * 24 * 3600
    public var maxBodySize: Long = 1024L * 1024L
    public var maxStorage: Long = 100L * 1024L * 1024L
}

public object StackAuditBridge {
    @Throws(IllegalArgumentException::class)
    public fun config(options: BridgeOptions): StackAuditConfig =
        StackAuditConfig.build {
            val rules: io.github.r1b2ns.stackaudit.SecretRules.Builder.() -> Unit = {
                options.extraHeaders.forEach(::header)
                options.extraFields.forEach(::jsonField)
                options.allowed.forEach(::allow)
            }
            secrets =
                when (options.secrets) {
                    SecretsMode.Mask -> Secrets.Mask(rules)
                    SecretsMode.Show -> Secrets.Show
                    SecretsMode.ShowAfterHandshake -> Secrets.ShowAfterHandshake(rules)
                }
            access =
                when (options.access) {
                    AccessMode.Open -> Access.Open
                    AccessMode.Hidden -> Access.Hidden
                    AccessMode.Handshake ->
                        Access.Handshake(
                            requireNotNull(options.publicKey) { "StackAudit: access .handshake requires a public key." },
                            options.sessionTtlSeconds.seconds,
                        )
                }
            retention = options.retentionSeconds.seconds
            maxBodySize = options.maxBodySize
            maxStorage = options.maxStorage
        }

    @Throws(IllegalArgumentException::class)
    public fun start(
        options: BridgeOptions,
        cipher: DataCipher?,
    ) {
        val config = config(options)
        StackAuditIos.cipher = cipher?.let(::DataCipherAdapter)
        StackAuditUi.install()
        StackAudit.start(config)
    }

    public val isCapturing: Boolean get() = StackAudit.isCapturing

    public val maxBodySize: Long get() = StackAudit.maxBodySize

    public fun recordHttp(
        source: String,
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: NSData?,
        status: Int,
        responseHeaders: Map<String, String>,
        responseBody: NSData?,
        startedAtMs: Long,
        durationMs: Long,
        error: String?,
        requestBodySize: Long,
        responseBodySize: Long,
    ) {
        StackAudit.record(
            HttpExchange(
                source = source,
                method = method,
                url = url,
                requestHeaders = requestHeaders.map { Header(it.key, it.value) },
                requestBody = requestBody?.toByteArray(),
                requestContentType = requestHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                status = status.takeIf { it > 0 },
                responseHeaders = responseHeaders.map { Header(it.key, it.value) },
                responseBody = responseBody?.toByteArray(),
                responseContentType = responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                startedAt = startedAtMs,
                durationMs = durationMs,
                error = error,
                requestBodySize = requestBodySize.takeIf { it >= 0 },
                responseBodySize = responseBodySize.takeIf { it >= 0 },
            ),
        )
    }

    public val isCapturingWebSockets: Boolean get() = StackAudit.isCapturingWebSockets

    public fun recordWebSocket(
        source: String,
        connectionId: String,
        url: String,
        kind: String,
        data: NSData?,
        isText: Boolean,
        code: Int,
        reason: String?,
        error: String?,
        timestampMs: Long,
    ) {
        val parsed = WebSocketKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return
        StackAudit.record(
            WebSocketFrame(
                source = source,
                connectionId = connectionId,
                url = url,
                kind = parsed,
                timestamp = timestampMs,
                data = data?.toByteArray()?.takeIf { it.size <= StackAudit.maxBodySize },
                isText = isText,
                code = code.takeIf { it > 0 },
                reason = reason,
                error = error,
            ),
        )
    }

    public val isCapturingStorage: Boolean get() = StackAudit.isCapturingStorage

    public val isCapturingDatabase: Boolean get() = StackAudit.isCapturingDatabase

    public fun recordStorage(
        source: String,
        store: String,
        key: String?,
        operation: String,
        oldValue: String?,
        newValue: String?,
        valueType: String?,
        timestampMs: Long,
    ) {
        val parsed = StorageOperation.entries.firstOrNull { it.name.equals(operation, ignoreCase = true) } ?: return
        StackAudit.record(StorageChange(source, store, key, parsed, timestampMs, oldValue, newValue, valueType))
    }

    public fun recordDatabase(
        source: String,
        database: String,
        sql: String,
        arguments: List<String?>,
        durationMs: Long,
        rowsAffected: Long,
        error: String?,
        timestampMs: Long,
    ) {
        StackAudit.record(
            DatabaseStatement(
                source = source,
                database = database,
                sql = sql,
                timestamp = timestampMs,
                arguments = arguments,
                durationMs = durationMs.takeIf { it >= 0 },
                rowsAffected = rowsAffected.takeIf { it >= 0 },
                error = error,
            ),
        )
    }

    public fun show(): Boolean = StackAudit.show()
}

public interface DataCipher {
    public fun seal(data: NSData): NSData

    public fun open(data: NSData): NSData
}

internal class DataCipherAdapter(
    private val cipher: DataCipher,
) : BlobCipher {
    override fun encrypt(plain: ByteArray): ByteArray = cipher.seal(plain.toNSData()).toByteArray()

    override fun decrypt(data: ByteArray): ByteArray = cipher.open(data.toNSData()).toByteArray()
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
