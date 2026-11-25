package io.github.r1b2ns.stackaudit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class Category(public val wire: String) {
    @SerialName("http")
    Http("http"),

    @SerialName("websocket")
    WebSocket("websocket"),

    @SerialName("storage")
    Storage("storage"),

    @SerialName("database")
    Database("database"),

    @SerialName("analytics")
    Analytics("analytics"),

    @SerialName("flag")
    Flag("flag"),

    @SerialName("audit")
    Audit("audit"),
    ;

    public companion object {
        public fun fromWire(value: String): Category = entries.firstOrNull { it.wire == value } ?: Audit
    }
}

@Serializable
public data class Header(
    val name: String,
    val value: String,
)

@Serializable
public data class BodyRef(
    val hash: String?,
    val size: Long,
    val contentType: String? = null,
    val stored: Boolean,
    val text: Boolean,
)

@Serializable
public sealed interface AuditPayload

@Serializable
@SerialName("http")
public data class HttpPayload(
    val method: String,
    val url: String,
    val requestHeaders: List<Header>,
    val requestBody: BodyRef?,
    val status: Int?,
    val responseHeaders: List<Header>,
    val responseBody: BodyRef?,
    val error: String? = null,
    val protocol: String? = null,
) : AuditPayload

@Serializable
public enum class WebSocketKind {
    @SerialName("open")
    Open,

    @SerialName("sent")
    Sent,

    @SerialName("received")
    Received,

    @SerialName("ping")
    Ping,

    @SerialName("pong")
    Pong,

    @SerialName("closing")
    Closing,

    @SerialName("closed")
    Closed,

    @SerialName("failed")
    Failed,
}

@Serializable
@SerialName("websocket")
public data class WebSocketPayload(
    val connectionId: String,
    val url: String,
    val kind: WebSocketKind,
    val body: BodyRef? = null,
    val code: Int? = null,
    val reason: String? = null,
    val error: String? = null,
) : AuditPayload

@Serializable
public enum class StorageOperation {
    @SerialName("read")
    Read,

    @SerialName("write")
    Write,

    @SerialName("remove")
    Remove,

    @SerialName("clear")
    Clear,
}

@Serializable
@SerialName("storage")
public data class StoragePayload(
    val store: String,
    val key: String?,
    val operation: StorageOperation,
    val oldValue: String? = null,
    val newValue: String? = null,
    val valueType: String? = null,
) : AuditPayload

@Serializable
@SerialName("database")
public data class DatabasePayload(
    val database: String,
    val sql: String,
    val arguments: List<String?> = emptyList(),
    val rowsAffected: Long? = null,
    val error: String? = null,
) : AuditPayload

@Serializable
public enum class ValidationStatus {
    @SerialName("valid")
    Valid,

    @SerialName("unknown_event")
    UnknownEvent,

    @SerialName("invalid")
    Invalid,

    @SerialName("not_checked")
    NotChecked,
}

@Serializable
@SerialName("analytics")
public data class AnalyticsPayload(
    val provider: String,
    val name: String,
    val params: Map<String, String?> = emptyMap(),
    val validation: ValidationStatus = ValidationStatus.NotChecked,
    val issues: List<String> = emptyList(),
) : AuditPayload

@Serializable
public sealed interface FlagValue {
    public val display: String

    @Serializable
    @SerialName("bool")
    public data class Bool(
        val value: Boolean,
    ) : FlagValue {
        override val display: String get() = value.toString()
    }

    @Serializable
    @SerialName("text")
    public data class Text(
        val value: String,
    ) : FlagValue {
        override val display: String get() = value
    }

    @Serializable
    @SerialName("number")
    public data class Number(
        val value: Double,
    ) : FlagValue {
        override val display: String get() = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}

@Serializable
public enum class FlagSource {
    @SerialName("default")
    Default,

    @SerialName("remote")
    Remote,

    @SerialName("override")
    Override,
}

@Serializable
@SerialName("flag")
public data class FlagPayload(
    val key: String,
    val provider: String,
    val defaultValue: FlagValue,
    val remoteValue: FlagValue? = null,
    val overrideValue: FlagValue? = null,
    val effective: FlagValue,
    val source: FlagSource,
    val changedBy: String? = null,
) : AuditPayload

@Serializable
public enum class MetaKind {
    @SerialName("started")
    Started,

    @SerialName("stopped")
    Stopped,

    @SerialName("dropped")
    Dropped,

    @SerialName("purged")
    Purged,

    @SerialName("cleared")
    Cleared,

    @SerialName("exported")
    Exported,

    @SerialName("capture_error")
    CaptureError,
}

@Serializable
@SerialName("meta")
public data class MetaPayload(
    val kind: MetaKind,
    val message: String,
    val count: Long = 0,
) : AuditPayload

@Serializable
public data class AuditEvent(
    val id: String,
    val timestamp: Long,
    val durationMs: Long?,
    val category: Category,
    val source: String,
    val traceId: String?,
    val title: String,
    val payload: AuditPayload,
    val prevHash: String?,
    val hash: String,
)

public data class AuditEventSummary(
    val id: String,
    val timestamp: Long,
    val durationMs: Long?,
    val category: Category,
    val source: String,
    val title: String,
    val method: String?,
    val host: String?,
    val status: Int?,
)

public data class ChainReport(
    val valid: Boolean,
    val checked: Int,
    val brokenAt: String?,
)

public class HttpExchange(
    public val source: String,
    public val method: String,
    public val url: String,
    public val requestHeaders: List<Header>,
    public val requestBody: ByteArray?,
    public val requestContentType: String?,
    public val status: Int?,
    public val responseHeaders: List<Header>,
    public val responseBody: ByteArray?,
    public val responseContentType: String?,
    public val startedAt: Long,
    public val durationMs: Long,
    public val error: String? = null,
    public val traceId: String? = null,
    public val protocol: String? = null,
    public val requestBodySize: Long? = null,
    public val responseBodySize: Long? = null,
)

public interface HttpRecorder {
    public val isCapturing: Boolean
    public val maxBodySize: Long

    public fun record(exchange: HttpExchange)
}

public class WebSocketFrame(
    public val source: String,
    public val connectionId: String,
    public val url: String,
    public val kind: WebSocketKind,
    public val timestamp: Long,
    public val data: ByteArray? = null,
    public val isText: Boolean = true,
    public val code: Int? = null,
    public val reason: String? = null,
    public val error: String? = null,
)

public interface WebSocketRecorder {
    public val isCapturingWebSockets: Boolean
    public val maxBodySize: Long

    public fun record(frame: WebSocketFrame)
}

public class StorageChange(
    public val source: String,
    public val store: String,
    public val key: String?,
    public val operation: StorageOperation,
    public val timestamp: Long,
    public val oldValue: String? = null,
    public val newValue: String? = null,
    public val valueType: String? = null,
)

public interface StorageRecorder {
    public val isCapturingStorage: Boolean

    public fun record(change: StorageChange)
}

public class DatabaseStatement(
    public val source: String,
    public val database: String,
    public val sql: String,
    public val timestamp: Long,
    public val arguments: List<Any?> = emptyList(),
    public val durationMs: Long? = null,
    public val rowsAffected: Long? = null,
    public val error: String? = null,
)

public interface DatabaseRecorder {
    public val isCapturingDatabase: Boolean

    public fun record(statement: DatabaseStatement)
}

public class AnalyticsCall(
    public val provider: String,
    public val name: String,
    public val params: Map<String, Any?>,
    public val timestamp: Long,
    public val validation: ValidationStatus = ValidationStatus.NotChecked,
    public val issues: List<String> = emptyList(),
)

public interface AnalyticsRecorder {
    public val isCapturingAnalytics: Boolean

    public fun record(call: AnalyticsCall)
}

public interface FlagRecorder {
    public val isCapturingFlags: Boolean

    public fun record(
        flag: FlagPayload,
        timestamp: Long,
    )
}

public fun interface AuditViewer {
    public fun present()
}
