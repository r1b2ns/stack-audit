package io.github.r1b2ns.stackaudit.demo

import io.github.r1b2ns.stackaudit.ktor.StackAuditKtor
import io.github.r1b2ns.stackaudit.okhttp.audited
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import android.content.Context
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.r1b2ns.stackaudit.ktor.auditedWebSocket
import io.github.r1b2ns.stackaudit.sqldelight.audited
import io.github.r1b2ns.stackaudit.storage.auditedSharedPreferences
import io.github.r1b2ns.stackaudit.okhttp.newAuditedWebSocket
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class Client { OkHttp, Ktor, OkHttpWebSocket, KtorWebSocket, SharedPreferences, SqlDelight }

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
    const val ECHO = "wss://echo.websocket.org/"

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
            Scenario("OkHttp WebSocket echo", Client.OkHttpWebSocket, "WS", "$ECHO?token=abcdefghijklmnop"),
            Scenario("Ktor WebSocket echo", Client.KtorWebSocket, "WS", ECHO),
            Scenario("SharedPreferences write", Client.SharedPreferences, "PREFS", "local://demo_prefs"),
            Scenario("SQLDelight statements", Client.SqlDelight, "SQL", "local://demo.db"),
        )
}

object DemoSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, "CREATE TABLE user(id INTEGER PRIMARY KEY, name TEXT, password TEXT)", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

const val MESSAGE = """{"type":"chat","text":"hello","password":"hunter2"}"""

class DemoRunner(
    private val context: Context,
) {
    private val okHttp = OkHttpClient.Builder().audited().build()
    private val database by lazy {
        AndroidSqliteDriver(DemoSchema, context, "demo.db").audited("demo.db")
    }
    private val ktor =
        HttpClient(Android) {
            install(StackAuditKtor)
        }
    private val ktorSockets =
        HttpClient(CIO) {
            install(WebSockets)
        }

    suspend fun run(scenario: Scenario): String =
        runCatching {
            when (scenario.client) {
                Client.OkHttp -> okHttp(scenario)
                Client.Ktor -> {
                    val response = ktor.get(scenario.url)
                    "${response.status.value} · ${response.bodyAsText().length} chars"
                }
                Client.OkHttpWebSocket -> okHttpSocket(scenario.url)
                Client.SharedPreferences -> {
                    val prefs = context.auditedSharedPreferences("demo_prefs")
                    prefs.edit().putString("theme", if (prefs.getString("theme", "light") == "light") "dark" else "light").apply()
                    prefs.edit().putString("access_token", "abcdefghijkl1234").putInt("launches", prefs.getInt("launches", 0) + 1).apply()
                    "3 writes"
                }
                Client.SqlDelight ->
                    withContext(Dispatchers.IO) {
                        database.execute(null, "INSERT INTO user(name, password) VALUES (?, ?)", 2) {
                            bindString(0, "ana")
                            bindString(1, "hunter2")
                        }
                        val count =
                            database.executeQuery(null, "SELECT COUNT(*) FROM user", { cursor ->
                                cursor.next()
                                QueryResult.Value(cursor.getLong(0))
                            }, 0).value
                        "$count users"
                    }
                Client.KtorWebSocket -> {
                    var echoed = ""
                    ktorSockets.auditedWebSocket(scenario.url) {
                        incoming.receive()
                        send(Frame.Text(MESSAGE))
                        echoed = (incoming.receive() as Frame.Text).readText()
                        close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                    }
                    "echo · ${echoed.length} chars"
                }
            }
        }.getOrElse { "Failed: ${it::class.simpleName}: ${it.message}" }

    private suspend fun okHttpSocket(url: String): String =
        withContext(Dispatchers.IO) {
            val replies = CountDownLatch(2)
            val listener =
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        replies.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) {
                        replies.countDown()
                    }
                }
            val socket = okHttp.newAuditedWebSocket(Request.Builder().url(url).build(), listener)
            socket.send(MESSAGE)
            socket.send(byteArrayOf(1, 2, 3).toByteString())
            val done = replies.await(10, TimeUnit.SECONDS)
            socket.close(1000, "done")
            if (done) "echo received" else "Timed out"
        }

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
