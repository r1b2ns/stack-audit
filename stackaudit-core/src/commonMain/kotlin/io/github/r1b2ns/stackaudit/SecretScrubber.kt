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

    public fun json(text: String): String = scrubJson(text, 0) ?: text

    private fun scrubJson(
        text: String,
        depth: Int,
    ): String? {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
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
                is JsonPrimitive -> {
                    val nested = if (node.isString && depth < MAX_NESTED_DEPTH && looksLikeJson(node.content)) scrubJson(node.content, depth + 1) else null
                    if (nested == null) {
                        node
                    } else {
                        changed = true
                        JsonPrimitive(nested)
                    }
                }
            }

        val result = walk(element)
        return if (changed) result.toString() else null
    }

    private fun looksLikeJson(value: String): Boolean {
        val trimmed = value.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length > 1)
    }

    public fun storageValue(
        key: String?,
        value: String?,
    ): String? {
        if (value == null) return null
        if (key != null && rules.isSecretField(key)) return mask(value)
        return body(value, null)
    }

    public fun sqlArguments(
        sql: String,
        arguments: List<String?>,
    ): List<String?> {
        val columns = SqlColumns.forPlaceholders(sql)
        return arguments.mapIndexed { index, value ->
            val column = columns.getOrNull(index)
            if (value != null && column != null && rules.isSecretField(column)) mask(value) else value
        }
    }

    public fun sql(sql: String): String = SqlLiterals.mask(sql, rules::isSecretField)

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
        private const val MAX_NESTED_DEPTH = 8

        public fun mask(value: String): String {
            if (value.isEmpty() || value.startsWith(MASK)) return value
            return if (value.length >= MIN_TAIL_SOURCE) MASK + value.takeLast(TAIL) else MASK
        }
    }
}

internal object SqlColumns {
    private val insert = Regex("""insert\s+(?:or\s+\w+\s+)?into\s+[`"\[]?\w+[`"\]]?\s*\(([^)]*)\)\s*values\s*\(([^)]*)\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val identifier = Regex("""[`"\[]?(\w+)[`"\]]?\s*$""")
    private val comparison = Regex("""(?:==|<>|!=|<=|>=|=|<|>|\blike\b|\bis\b)\s*$""", RegexOption.IGNORE_CASE)

    fun forPlaceholders(sql: String): List<String?> {
        insert.find(sql)?.let { match ->
            val columns = match.groupValues[1].split(',').map { it.trim().trim('`', '"', '[', ']') }
            val values = match.groupValues[2].split(',').map { it.trim() }
            val mapped = values.mapIndexedNotNull { index, value -> if (value == "?") columns.getOrNull(index) else null }
            val rest = sql.substring(match.range.last + 1)
            return mapped + forComparisons(rest)
        }
        return forComparisons(sql)
    }

    private fun forComparisons(sql: String): List<String?> {
        val result = mutableListOf<String?>()
        var index = sql.indexOf('?')
        while (index >= 0) {
            val before = sql.substring(0, index)
            val operator = comparison.find(before)
            result +=
                if (operator != null) {
                    identifier.find(before.substring(0, operator.range.first))?.groupValues?.get(1)
                } else {
                    null
                }
            index = sql.indexOf('?', index + 1)
        }
        return result
    }
}

internal object SqlLiterals {
    private val insert = Regex("""insert\s+(?:or\s+\w+\s+)?into\s+[`"\[]?[\w.]+[`"\]]?\s*\(([^)]*)\)\s*values\s*""", RegexOption.IGNORE_CASE)
    private val comparison =
        Regex(
            """[`"\[]?(\w+)[`"\]]?\s*(?:==|<>|!=|<=|>=|=|<|>|\blike\b|\bis\b)\s*(x'[0-9a-f]*'|'(?:[^']|'')*'|-?\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )

    fun mask(
        sql: String,
        isSecret: (String) -> Boolean,
    ): String {
        val ranges = mutableListOf<IntRange>()
        insert.findAll(sql).forEach { match ->
            val columns = match.groupValues[1].split(',').map { it.trim().trim('`', '"', '[', ']') }
            tuples(sql, match.range.last + 1).forEach { items ->
                items.forEachIndexed { index, range ->
                    val column = columns.getOrNull(index)
                    if (column != null && isSecret(column) && isLiteral(sql.substring(range.first, range.last + 1))) ranges += range
                }
            }
        }
        val strings = strings(sql)
        comparison.findAll(sql).forEach { match ->
            val value = match.groupValues[2]
            val literal = (match.range.last - value.length + 1)..match.range.last
            val insideString = strings.any { match.range.first > it.first && match.range.first <= it.last }
            if (!insideString && isSecret(match.groupValues[1]) && ranges.none { it.first <= literal.first && literal.last <= it.last }) {
                ranges += literal
            }
        }
        if (ranges.isEmpty()) return sql
        val out = StringBuilder()
        var cursor = 0
        ranges.sortedBy { it.first }.forEach { range ->
            out.append(sql, cursor, range.first)
            out.append(replacement(sql.substring(range.first, range.last + 1)))
            cursor = range.last + 1
        }
        out.append(sql, cursor, sql.length)
        return out.toString()
    }

    private fun replacement(literal: String): String =
        if (literal.startsWith("'")) {
            "'" + SecretScrubber.mask(literal.substring(1, literal.length - 1).replace("''", "'")) + "'"
        } else {
            "'" + SecretScrubber.MASK + "'"
        }

    private fun isLiteral(text: String): Boolean =
        text.startsWith("'") || text.startsWith("x'", ignoreCase = true) || text.toDoubleOrNull() != null

    private fun tuples(
        sql: String,
        start: Int,
    ): List<List<IntRange>> {
        val result = mutableListOf<List<IntRange>>()
        var i = skipSpace(sql, start)
        while (i < sql.length && sql[i] == '(') {
            val items = mutableListOf<IntRange>()
            var itemStart = i + 1
            var depth = 0
            var j = i + 1
            var closed = false
            while (j < sql.length) {
                val c = sql[j]
                when {
                    c == '\'' -> j = endOfString(sql, j)
                    c == '(' -> depth++
                    c == ')' && depth > 0 -> depth--
                    c == ',' && depth == 0 -> {
                        items += trimmed(sql, itemStart, j)
                        itemStart = j + 1
                    }
                    c == ')' -> {
                        items += trimmed(sql, itemStart, j)
                        closed = true
                    }
                }
                if (closed) break
                j++
            }
            if (!closed) break
            result += items
            i = skipSpace(sql, j + 1)
            if (i < sql.length && sql[i] == ',') i = skipSpace(sql, i + 1) else break
        }
        return result
    }

    private fun strings(sql: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var i = 0
        while (i < sql.length) {
            if (sql[i] == '\'') {
                val end = endOfString(sql, i)
                result += i..end
                i = end + 1
            } else {
                i++
            }
        }
        return result
    }

    private fun endOfString(
        sql: String,
        quote: Int,
    ): Int {
        var k = quote + 1
        while (k < sql.length) {
            if (sql[k] == '\'') {
                if (k + 1 < sql.length && sql[k + 1] == '\'') k += 2 else return k
            } else {
                k++
            }
        }
        return sql.length - 1
    }

    private fun trimmed(
        sql: String,
        from: Int,
        until: Int,
    ): IntRange {
        var a = from
        var b = until - 1
        while (a <= b && sql[a].isWhitespace()) a++
        while (b >= a && sql[b].isWhitespace()) b--
        return a..b
    }

    private fun skipSpace(
        sql: String,
        from: Int,
    ): Int {
        var i = from
        while (i < sql.length && sql[i].isWhitespace()) i++
        return i
    }
}
