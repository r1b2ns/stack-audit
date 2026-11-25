package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class HandshakeVerifierTest {
    private val signer = TestSigner()
    private val now = 1_700_000_000_000L
    private val nonces =
        object : NonceStore {
            val used = mutableSetOf<String>()

            override fun seen(nonce: String) = nonce in used

            override fun remember(
                nonce: String,
                expiresAt: Long,
            ) {
                used += nonce
            }
        }
    private val verifier =
        HandshakeVerifier(HandshakeVerifier.parsePublicKey(signer.publicKey), "jvm", { "device-1" }, 30.minutes, nonces, { now })

    private fun rejected(token: String): String = assertIs<HandshakeResult.Rejected>(verifier.verify(token)).reason

    @Test
    fun acceptsAValidToken() {
        val result = verifier.verify(signer.token(scope = listOf("read", "flags", "unknown"), iat = now, exp = now + 60_000))
        val session = assertIs<HandshakeResult.Accepted>(result).session
        assertEquals(setOf(Scope.Read, Scope.Flags), session.scopes)
        assertEquals(now + 30.minutes.inWholeMilliseconds, session.expiresAt)
    }

    @Test
    fun rejectsInvalidTokens() {
        assertEquals("malformed token", rejected("abc"))
        assertEquals("invalid signature", rejected(TestSigner().token(iat = now, exp = now + 1000)))
        assertEquals("token is for another app", rejected(signer.token(appId = "other", iat = now, exp = now + 1000)))
        assertEquals("token expired", rejected(signer.token(iat = now - 2000, exp = now - 1000)))
        assertEquals("token is not valid yet", rejected(signer.token(iat = now + 10.minutes.inWholeMilliseconds, exp = now + 20.minutes.inWholeMilliseconds)))
        assertEquals("token lifetime is too long", rejected(signer.token(iat = now, exp = now + 25.hours.inWholeMilliseconds)))
        assertEquals("token has no known scope", rejected(signer.token(scope = listOf("admin"), iat = now, exp = now + 1000)))
        assertEquals("token is for another device", rejected(signer.token(deviceId = "device-2", iat = now, exp = now + 1000)))
        assertEquals("invalid claims", rejected(signer.sign("not json".encodeToByteArray())))
    }

    @Test
    fun acceptsMatchingDeviceAndRejectsReplay() {
        val token = signer.token(deviceId = "device-1", nonce = "n1", iat = now, exp = now + 1000)
        assertIs<HandshakeResult.Accepted>(verifier.verify(token))
        assertEquals("token was already used", rejected(token))
    }

    @Test
    fun parsesPublicKeysInBase64AndHex() {
        val bytes = HandshakeVerifier.parsePublicKey(signer.publicKey)
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(bytes.toList(), HandshakeVerifier.parsePublicKey(hex).toList())
        assertFailsWith<IllegalArgumentException> { HandshakeVerifier.parsePublicKey("short") }
    }
}
