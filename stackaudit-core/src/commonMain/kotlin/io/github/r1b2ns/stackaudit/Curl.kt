package io.github.r1b2ns.stackaudit

public object Curl {
    public fun from(
        payload: HttpPayload,
        requestBody: String? = null,
    ): String =
        buildString {
            append("curl -X ").append(payload.method).append(' ').append(quote(payload.url))
            payload.requestHeaders.forEach { append(" -H ").append(quote("${it.name}: ${it.value}")) }
            if (requestBody != null) append(" --data-raw ").append(quote(requestBody))
        }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
