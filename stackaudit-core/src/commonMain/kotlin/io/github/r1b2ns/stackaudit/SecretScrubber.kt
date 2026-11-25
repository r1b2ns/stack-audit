package io.github.r1b2ns.stackaudit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public class SecretScrubber(private val rules: SecretRules) {
    public fun headers(headers: List<Header>): List<Header> =
        headers.map { if (rules.isSecretHeader(it.name)) Header(it.name, maskHeader(it.value)) else it }

    public fun url(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val query = url.substring(queryStart + 1, fragmentStart)
        return url.substring(0, queryStart + 1) + form(query) + url.substring(fragmentStart)
    }

    public fun body(
        text: String,
        contentType: String?,
    ): String {
        val type = contentType?.lowercase().orEmpty()
        return when {
            "x-www-form-urlencoded" in type -> form(text)
            "json" in type -> json(text)
            type.isEmpty() && text.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> json(text)
            else -> text
        }
    }

    public fun form(text: String): String {
        if (text.isEmpty()) return text
        var changed = false
        val parts =
            text.split('&').map { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@map part
                val name = decode(part.substring(0, eq))
                if (rules.isSecretField(name)) {
                    changed = true
                    part.substring(0, eq + 1) + mask(decode(part.substring(eq + 1)))
                } else {
                    part
                }
            }
        return if (changed) parts.joinToString("&") else text
    }

    public fun json(text: String): String {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return text
        var changed = false

        fun walk(node: JsonElement): JsonElement =
            when (node) {
                is JsonObject ->
                    JsonObject(
                        node.mapValues { (key, value) ->
                            if (rules.isSecretField(key)) {
                                changed = true
                                when (value) {
                                    is JsonPrimitive -> JsonPrimitive(mask(value.content))
                                    else -> JsonPrimitive(MASK)
                                }
                            } else {
                                walk(value)
                            }
                        },
                    )
                is JsonArray -> JsonArray(node.map(::walk))
                else -> node
            }

        val result = walk(element)
        return if (changed) result.toString() else text
    }

    private fun maskHeader(value: String): String {
        val space = value.indexOf(' ')
        val scheme = if (space > 0) value.substring(0, space) else ""
        return if (scheme.equals("Bearer", true) || scheme.equals("Basic", true)) {
            "$scheme ${mask(value.substring(space + 1))}"
        } else {
            mask(value)
        }
    }

    private fun decode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes += byte.toByte()
                    i += 3
                    continue
                }
            }
            if (c == '+') bytes += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    public companion object {
        public const val MASK: String = "••••"
        private const val TAIL = 4
        private const val MIN_TAIL_SOURCE = 12

        public fun mask(value: String): String {
            if (value.isEmpty() || value.startsWith(MASK)) return value
            return if (value.length >= MIN_TAIL_SOURCE) MASK + value.takeLast(TAIL) else MASK
        }
    }
}
