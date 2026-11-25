package io.github.r1b2ns.stackaudit

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class TestSigner {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    val publicKey: String =
        pair.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }

    fun token(
        appId: String = "jvm",
        scope: List<String> = listOf("read"),
        iat: Long,
        exp: Long,
        nonce: String = java.util.UUID.randomUUID().toString(),
        deviceId: String? = null,
    ): String {
        val claims = HandshakeClaims(appId, deviceId, scope, iat, exp, nonce)
        val payload = AuditJson.encodeToString(HandshakeClaims.serializer(), claims).encodeToByteArray()
        return sign(payload)
    }

    fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance("Ed25519").apply { initSign(pair.private) }
        signature.update(payload)
        val url = Base64.getUrlEncoder().withoutPadding()
        return url.encodeToString(payload) + "." + url.encodeToString(signature.sign())
    }
}
