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

public fun interface AuditViewer {
    public fun present()
}
