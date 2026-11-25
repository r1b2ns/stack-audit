package io.github.r1b2ns.stackaudit.demo

import io.github.r1b2ns.stackaudit.ktor.StackAuditKtor
import io.github.r1b2ns.stackaudit.okhttp.audited
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class Client { OkHttp, Ktor }

data class Scenario(
    val title: String,
    val client: Client,
    val method: String,
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

object DemoScenarios {
    const val BIN = "https://httpbin.org"

    val all: List<Scenario> =
        listOf(
            Scenario("GET JSON", Client.OkHttp, "GET", "https://jsonplaceholder.typicode.com/posts/1"),
            Scenario(
                "POST login with secrets",
                Client.OkHttp,
                "POST",
                "$BIN/post?token=abcdefghijklmnop",
                body = """{"user":"ana","password":"hunter2","access_token":"abcdefghijkl1234"}""",
                headers = mapOf("Authorization" to "Bearer abcdefghijkl1234", "X-Session" to "session-123"),
            ),
            Scenario("Redirect 302", Client.OkHttp, "GET", "$BIN/redirect-to?url=%2Fget"),
            Scenario("Not found 404", Client.OkHttp, "GET", "$BIN/status/404"),
            Scenario("Server error 500", Client.OkHttp, "GET", "$BIN/status/500"),
            Scenario("Slow 2 s", Client.OkHttp, "GET", "$BIN/delay/2"),
            Scenario("Large body 2 MB", Client.OkHttp, "GET", "https://speed.cloudflare.com/__down?bytes=2000000"),
            Scenario("Image", Client.OkHttp, "GET", "$BIN/image/png"),
            Scenario("Unknown host", Client.OkHttp, "GET", "https://does-not-exist.stackaudit.invalid/"),
            Scenario("Ktor GET", Client.Ktor, "GET", "$BIN/get?source=ktor"),
        )
}

class DemoRunner {
    private val okHttp = OkHttpClient.Builder().audited().build()
    private val ktor =
        HttpClient(Android) {
            install(StackAuditKtor)
        }

    suspend fun run(scenario: Scenario): String =
        runCatching {
            when (scenario.client) {
                Client.OkHttp -> okHttp(scenario)
                Client.Ktor -> {
                    val response = ktor.get(scenario.url)
                    "${response.status.value} · ${response.bodyAsText().length} chars"
                }
            }
        }.getOrElse { "Failed: ${it::class.simpleName}: ${it.message}" }

    private suspend fun okHttp(scenario: Scenario): String =
        withContext(Dispatchers.IO) {
            val body = scenario.body?.toRequestBody("application/json".toMediaType())
            val request =
                Request
                    .Builder()
                    .url(scenario.url)
                    .method(scenario.method, body)
                    .apply { scenario.headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            okHttp.newCall(request).execute().use { "${it.code} · ${it.body.bytes().size} bytes" }
        }
}
