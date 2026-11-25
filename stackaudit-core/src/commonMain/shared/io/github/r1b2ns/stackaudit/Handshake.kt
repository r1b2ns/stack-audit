package io.github.r1b2ns.stackaudit

import kotlinx.serialization.Serializable

public enum class Scope(
    public val wire: String,
) {
    Read("read"),
    ReadSecrets("read_secrets"),
    Flags("flags"),
    ;

    public companion object {
        public fun fromWire(value: String): Scope? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
public data class HandshakeClaims(
    val appId: String,
    val deviceId: String? = null,
    val scope: List<String>,
    val iat: Long,
    val exp: Long,
    val nonce: String,
)

public data class AuditSession(
    val scopes: Set<Scope>,
    val expiresAt: Long,
) {
    public fun allows(
        scope: Scope,
        now: Long,
    ): Boolean = now < expiresAt && scope in scopes
}

public sealed interface HandshakeResult {
    public data class Accepted(
        val session: AuditSession,
    ) : HandshakeResult

    public data class Rejected(
        val reason: String,
    ) : HandshakeResult
}
