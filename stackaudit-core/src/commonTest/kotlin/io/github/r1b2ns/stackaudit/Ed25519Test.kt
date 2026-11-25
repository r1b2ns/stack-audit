package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val publicKey = hex("a2fbab3de4f420bbc47eb7e83b6ac759cd239c2f1841d152e4d8eaadd19aad40")
    private val signature =
        hex("ac605c1e9060a6f0f31c1d543c6439151f7e644cca6b538f0794dad7b227c97c05465b8e0d3080821044f9a43affe7bda5ea8cf8e89d18d67e7996bc41ac5d02")
    private val message = "stackaudit".encodeToByteArray()

    @Test
    fun sha512MatchesReference() {
        assertEquals(
            "c1626aac47757d8596f85951c41b0237a63c9ab6d5f0433597cbeee2f3858dee5b446ae783bc3b4ece5792b9de81bfb255a19a6a7acc88e503d260c5560cb993",
            Sha512.digest(message).hex(),
        )
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Sha512.digest(ByteArray(0)).hex(),
        )
        assertEquals(128, Sha512.digest(ByteArray(200) { 1 }).hex().length)
    }

    @Test
    fun verifiesAValidSignature() {
        assertTrue(Ed25519.verify(signature, message, publicKey))
    }

    @Test
    fun rejectsTampering() {
        assertFalse(Ed25519.verify(signature, "stackaudiT".encodeToByteArray(), publicKey))
        assertFalse(Ed25519.verify(signature.copyOf().also { it[10] = (it[10] + 1).toByte() }, message, publicKey))
        assertFalse(Ed25519.verify(signature, message, publicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertFalse(Ed25519.verify(signature.copyOf(63), message, publicKey))
    }
}
