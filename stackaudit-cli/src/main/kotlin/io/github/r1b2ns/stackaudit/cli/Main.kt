package io.github.r1b2ns.stackaudit.cli

import io.github.r1b2ns.stackaudit.HandshakeClaims
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    exitProcess(Cli(System.currentTimeMillis()).run(args, System.out, System.err))
}

class Cli(
    private val now: Long,
) {
    fun run(
        args: Array<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val command = args.firstOrNull()
        val options = parse(args.drop(1))
        return try {
            when (command) {
                "keygen" -> keygen(File(options["out"] ?: "."), out)
                "sign" -> sign(options, out)
                else -> {
                    err.println(USAGE)
                    2
                }
            }
        } catch (error: IllegalArgumentException) {
            err.println("error: ${error.message}")
            2
        }
    }

    private fun keygen(
        dir: File,
        out: PrintStream,
    ): Int {
        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val file = File(dir, "stackaudit.key")
        require(!file.exists()) { "${file.path} already exists" }
        file.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        file.setReadable(false, false)
        file.setReadable(true, true)
        val encoded = pair.public.encoded
        val publicKey = Base64.getEncoder().encodeToString(encoded.copyOfRange(encoded.size - 32, encoded.size))
        out.println("private key: ${file.path} (keep it out of the app and out of git)")
        out.println("public key: $publicKey")
        return 0
    }

    private fun sign(
        options: Map<String, String>,
        out: PrintStream,
    ): Int {
        val keyFile = File(requireNotNull(options["key"]) { "--key is required" })
        val appId = requireNotNull(options["app-id"]) { "--app-id is required" }
        val scopes = (options["scope"] ?: "read").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(scopes.all { it in KNOWN_SCOPES }) { "--scope accepts ${KNOWN_SCOPES.joinToString()}" }
        val ttl = duration(options["ttl"] ?: "15m")
        require(ttl <= 24.hours) { "--ttl must be at most 24h" }
        val claims =
            HandshakeClaims(
                appId = appId,
                deviceId = options["device"],
                scope = scopes,
                iat = now,
                exp = now + ttl.inWholeMilliseconds,
                nonce = UUID.randomUUID().toString(),
            )
        val token = token(claims, loadKey(keyFile))
        out.println(token)
        options["scheme"]?.let { out.println("${it}://stackaudit/open?token=${URLEncoder.encode(token, "UTF-8")}") }
        return 0
    }

    companion object {
        val KNOWN_SCOPES = setOf("read", "read_secrets", "flags")

        val USAGE =
            """
            usage:
              stackaudit-cli keygen [--out <dir>]
              stackaudit-cli sign --key <file> --app-id <id> [--scope read,read_secrets,flags] [--ttl 15m] [--device <id>] [--scheme <app scheme>]
            """.trimIndent()

        fun parse(args: List<String>): Map<String, String> {
            val result = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val name = args[i].removePrefix("--")
                require(args[i].startsWith("--") && i + 1 < args.size) { "expected --name value at ${args[i]}" }
                result[name] = args[i + 1]
                i += 2
            }
            return result
        }

        fun duration(text: String): Duration {
            val value = text.dropLast(1).toLongOrNull()
            require(value != null && value > 0) { "invalid duration $text, use 15m or 2h" }
            return when (text.last()) {
                'm' -> value.minutes
                'h' -> value.hours
                else -> throw IllegalArgumentException("invalid duration $text, use 15m or 2h")
            }
        }

        fun loadKey(file: File): PrivateKey {
            require(file.exists()) { "${file.path} not found" }
            val bytes = Base64.getDecoder().decode(file.readText().trim())
            return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }

        fun token(
            claims: HandshakeClaims,
            key: PrivateKey,
        ): String {
            val payload = Json.encodeToString(HandshakeClaims.serializer(), claims).encodeToByteArray()
            val signature = Signature.getInstance("Ed25519").apply { initSign(key) }
            signature.update(payload)
            val url = Base64.getUrlEncoder().withoutPadding()
            return url.encodeToString(payload) + "." + url.encodeToString(signature.sign())
        }
    }
}
