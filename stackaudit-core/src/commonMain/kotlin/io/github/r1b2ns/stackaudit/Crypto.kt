package io.github.r1b2ns.stackaudit

internal expect fun sha256(bytes: ByteArray): ByteArray

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun sha256Hex(bytes: ByteArray): String = sha256(bytes).hex()

internal fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

public interface BlobCipher {
    public fun encrypt(plain: ByteArray): ByteArray

    public fun decrypt(data: ByteArray): ByteArray
}

internal object PlainCipher : BlobCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain

    override fun decrypt(data: ByteArray): ByteArray = data
}
