package io.github.r1b2ns.stackaudit.cli

import io.github.r1b2ns.stackaudit.HandshakeClaims
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private val now = 1_700_000_000_000L

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(now).run(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun keygenThenSignProducesAVerifiableToken() {
        val dir = createTempDirectory().toFile()
        val (code, out, _) = run("keygen", "--out", dir.path)
        assertEquals(0, code)
        val publicKey = out.lines().first { it.startsWith("public key: ") }.removePrefix("public key: ")
        assertEquals(32, Base64.getDecoder().decode(publicKey).size)

        val (signCode, signed, _) =
            run("sign", "--key", File(dir, "stackaudit.key").path, "--app-id", "com.example", "--scope", "read,flags", "--ttl", "10m", "--scheme", "myapp")
        assertEquals(0, signCode)
        val (token, link) = signed.trim().lines()
        assertEquals(token, URLDecoder.decode(link.substringAfter("token="), "UTF-8"))
        assertTrue(link.startsWith("myapp://stackaudit/open?token="))

        val (payload, signature) = token.split('.').map { Base64.getUrlDecoder().decode(it) }
        val claims = Json.decodeFromString(HandshakeClaims.serializer(), payload.decodeToString())
        assertEquals("com.example", claims.appId)
        assertEquals(listOf("read", "flags"), claims.scope)
        assertEquals(now + 600_000, claims.exp)

        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + Base64.getDecoder().decode(publicKey)))
        val verifier = Signature.getInstance("Ed25519").apply { initVerify(key) }
        verifier.update(payload)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun rejectsBadInput() {
        assertEquals(2, run("nope").first)
        assertEquals(2, run("sign", "--app-id", "x").first)
        assertTrue(run("sign", "--key", "missing.key", "--app-id", "x").third.contains("not found"))
        assertEquals(2, run("sign", "--key", "k", "--app-id", "x", "--scope", "admin").first)
        assertEquals(2, run("sign", "--key", "k", "--app-id", "x", "--ttl", "48h").first)
    }

    @Test
    fun keygenRefusesToOverwrite() {
        val dir = createTempDirectory().toFile()
        assertEquals(0, run("keygen", "--out", dir.path).first)
        assertTrue(run("keygen", "--out", dir.path).third.contains("already exists"))
    }

    @Test
    fun parsesDurations() {
        assertEquals(15L, Cli.duration("15m").inWholeMinutes)
        assertEquals(2L, Cli.duration("2h").inWholeHours)
    }
}
