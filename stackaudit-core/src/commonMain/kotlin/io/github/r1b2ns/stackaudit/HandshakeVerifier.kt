package io.github.r1b2ns.stackaudit

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

public interface NonceStore {
    public fun seen(nonce: String): Boolean

    public fun remember(
        nonce: String,
        expiresAt: Long,
    )
}

internal class HandshakeVerifier(
    private val publicKey: ByteArray,
    private val appId: String,
    private val deviceId: () -> String?,
    private val sessionTtl: Duration,
    private val nonces: NonceStore,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun verify(token: String): HandshakeResult {
        val parts = token.trim().split('.')
        if (parts.size != 2) return reject("malformed token")
        val payload = decode(parts[0]) ?: return reject("malformed payload")
        val signature = decode(parts[1]) ?: return reject("malformed signature")
        if (!Ed25519.verify(signature, payload, publicKey)) return reject("invalid signature")
        val claims =
            runCatching { json.decodeFromString(HandshakeClaims.serializer(), payload.decodeToString()) }.getOrNull()
                ?: return reject("invalid claims")
        val time = now()
        if (claims.appId != appId) return reject("token is for another app")
        claims.deviceId?.let { expected -> if (expected != deviceId()) return reject("token is for another device") }
        if (time < claims.iat - CLOCK_SKEW.inWholeMilliseconds) return reject("token is not valid yet")
        if (time > claims.exp) return reject("token expired")
        if (claims.exp - claims.iat > MAX_LIFETIME.inWholeMilliseconds) return reject("token lifetime is too long")
        val scopes = claims.scope.mapNotNull(Scope::fromWire).toSet()
        if (scopes.isEmpty()) return reject("token has no known scope")
        if (nonces.seen(claims.nonce)) return reject("token was already used")
        nonces.remember(claims.nonce, claims.exp)
        return HandshakeResult.Accepted(AuditSession(scopes, time + sessionTtl.inWholeMilliseconds))
    }

    private fun reject(reason: String) = HandshakeResult.Rejected(reason)

    companion object {
        val CLOCK_SKEW = 5.minutes
        val MAX_LIFETIME = 24.hours

        fun decode(text: String): ByteArray? {
            val normalized = text.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            return runCatching { Base64.decode(padded) }.getOrNull()
        }

        fun parsePublicKey(text: String): ByteArray {
            val trimmed = text.trim()
            val bytes =
                if (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    ByteArray(32) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                } else {
                    decode(trimmed)
                }
            require(bytes != null && bytes.size == 32) { "StackAudit: Access.Handshake publicKey must be 32 bytes in base64 or hex." }
            return bytes
        }
    }
}
