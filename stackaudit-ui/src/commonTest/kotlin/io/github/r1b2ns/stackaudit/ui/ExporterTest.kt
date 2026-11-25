package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.AuditEvent
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.StoragePayload
import io.github.r1b2ns.stackaudit.StorageOperation
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExporterTest {
    private val base = 1_700_000_000_000
    private val exporter = Exporter(zone = TimeZone.UTC)

    private fun event(
        method: String = "GET",
        url: String = "https://api.test/v1/users?token=abcdef123456&page=2",
        status: Int? = 200,
        requestHeaders: List<Header> =
            listOf(
                Header("Authorization", "Bearer abcdefghijklmnop"),
                Header("Host", "api.test"),
                Header("Content-Length", "13"),
                Header("Accept", "application/json"),
            ),
        requestBody: String? = null,
        responseBody: String? = """{"ok":true}""",
    ): ExportEvent {
        val payload =
            HttpPayload(
                method = method,
                url = url,
                requestHeaders = requestHeaders,
                requestBody = requestBody?.let { BodyRef("rq", it.length.toLong(), "application/json", stored = true, text = true) },
                status = status,
                responseHeaders = listOf(Header("Content-Type", "application/json")),
                responseBody = responseBody?.let { BodyRef("rs", it.length.toLong(), "application/json", stored = true, text = true) },
                protocol = "h2",
            )
        val audit = AuditEvent("e1", base, 42, Category.Http, "okhttp", null, "$method api.test/v1/users ${status ?: ""}", payload, null, "hash")
        return ExportEvent(audit, requestBody, responseBody)
    }

    private fun storage(): ExportEvent {
        val payload = StoragePayload("prefs", "theme", StorageOperation.Write, newValue = "dark")
        return ExportEvent(AuditEvent("s1", base, null, Category.Storage, "app", null, "prefs: theme", payload, null, "hash"))
    }

    @Test
    fun formatsTimestampsAndFileStamps() {
        assertEquals("2023-11-14T22:13:20.000Z", exporter.timestamp(base))
        assertEquals("20231114-221320", exporter.fileStamp(base))
    }

    @Test
    fun curlOmitsGetMethodAndTransportHeadersAndMasksSecrets() {
        val curl = exporter.curl(event(), includeBody = true)
        val lines = curl.split(" \\\n  ")
        assertTrue(lines.first().startsWith("curl 'https://api.test/v1/users?token=••••"))
        assertFalse("-X" in lines.first())
        assertFalse(curl.contains("Host:"))
        assertFalse(curl.contains("Content-Length"))
        assertTrue(lines.contains("-H 'Authorization: Bearer ••••mnop'"))
        assertTrue(lines.contains("-H 'Accept: application/json'"))
        assertFalse(curl.contains("abcdefghijklmnop"))
    }

    @Test
    fun curlCarriesMethodAndMaskedBodyWhenAsked() {
        val item = event(method = "POST", requestBody = """{"password":"hunter2","name":"a"}""")
        val withBody = exporter.curl(item, includeBody = true)
        assertTrue(withBody.startsWith("curl -X POST "))
        assertTrue("--data-raw" in withBody)
        assertFalse("hunter2" in withBody)
        assertFalse("--data-raw" in exporter.curl(item, includeBody = false))
        assertTrue(exporter.curl(storage(), includeBody = true).startsWith("#"))
    }

    @Test
    fun csvHasHeaderOneLinePerEventAndQuotesFields() {
        val csv = exporter.batch(listOf(event(url = "https://api.test/a,b"), storage()), BatchFormat.Csv, includeBodies = true)
        val lines = csv.trimEnd().lines()
        assertEquals(Exporter.CSV_HEADER, lines[0])
        assertEquals("2023-11-14T22:13:20.000Z,outbound,GET,api.test,\"/a,b\",200,0,11,Network", lines[1])
        assertEquals("2023-11-14T22:13:20.000Z,inbound,KV,prefs: theme,app,,0,0,Storage", lines[2])
    }

    @Test
    fun jsonBatchIsSortedMaskedAndHonoursBodies() {
        val text = exporter.batch(listOf(event()), BatchFormat.Json, includeBodies = true)
        val first = Json.parseToJsonElement(text).jsonArray.first().jsonObject
        assertEquals(first.keys.sorted(), first.keys.toList())
        assertEquals("https://api.test/v1/users?token=••••3456&page=2", first["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer ••••mnop", first["requestHeaders"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", first["responseBody"]!!.jsonPrimitive.content)
        val noBodies = Json.parseToJsonElement(exporter.batch(listOf(event()), BatchFormat.Json, includeBodies = false)).jsonArray.first().jsonObject
        assertEquals("null", noBodies["responseBody"].toString())
        assertTrue(text.startsWith("[\n  {"))
    }

    @Test
    fun harKeepsOnlyHttpEntries() {
        val har = Json.parseToJsonElement(exporter.batch(listOf(event(), storage()), BatchFormat.Har, includeBodies = true)).jsonObject
        val log = har["log"]!!.jsonObject
        assertEquals("1.2", log["version"]!!.jsonPrimitive.content)
        val entries = log["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        val request = entries.first().jsonObject["request"]!!.jsonObject
        assertEquals("GET", request["method"]!!.jsonPrimitive.content)
        assertEquals(2, request["queryString"]!!.jsonArray.size)
        val content = entries.first().jsonObject["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals("""{"ok":true}""", content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun textSummaryIsReadable() {
        val text = exporter.text(event(), includeBody = true)
        val lines = text.lines()
        assertEquals("GET https://api.test/v1/users?token=••••3456&page=2", lines[0])
        assertEquals("Status: 200 OK · 42 ms", lines[1])
        assertEquals("When: Nov 14, 2023 at 10:13:20 PM", lines[2])
        assertTrue("Sent: 0 bytes · Received: 11 bytes" in lines)
        assertTrue("Response:" in lines)
        assertFalse("Response:" in exporter.text(event(), includeBody = false))
    }

    @Test
    fun countsMaskedHeaders() {
        assertEquals(1, exporter.maskedHeaderCount(event()))
        assertEquals(0, exporter.maskedHeaderCount(storage()))
    }
}
