package io.github.r1b2ns.stackaudit

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class Ed25519JdkTest {
    @Test
    fun verifiesSignaturesMadeByTheJdk() {
        repeat(20) { round ->
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val encoded = pair.public.encoded
            val raw = encoded.copyOfRange(encoded.size - 32, encoded.size)
            val message = Random(round).nextBytes(round * 37)
            val signer = Signature.getInstance("Ed25519").apply { initSign(pair.private) }
            signer.update(message)
            val signature = signer.sign()
            assertTrue(Ed25519.verify(signature, message, raw), "round $round")
        }
    }
}
