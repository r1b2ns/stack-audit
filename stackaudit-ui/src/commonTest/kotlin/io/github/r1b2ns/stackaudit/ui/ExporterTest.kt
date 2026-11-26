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
        assertTrue("Response Body:" in lines)
        assertFalse("Response Body:" in exporter.text(event(), includeBody = false))
    }

    @Test
    fun textCarriesHeadersAndBodiesOfRequestAndResponse() {
        val item = event(method = "POST", requestBody = """{"password":"hunter2","name":"a"}""")
        val lines = exporter.text(item, includeBody = true).lines()
        val requestHeaders = lines.indexOf("Request Headers:")
        val requestBody = lines.indexOf("Request Body:")
        val responseHeaders = lines.indexOf("Response Headers:")
        val responseBody = lines.indexOf("Response Body:")
        assertTrue(requestHeaders in 0 until requestBody, lines.toString())
        assertTrue(requestBody < responseHeaders && responseHeaders < responseBody, lines.toString())
        assertEquals("Authorization: Bearer ••••mnop", lines[requestHeaders + 1])
        assertTrue("Accept: application/json" in lines)
        assertTrue(lines[requestBody + 1].contains("\"name\":\"a\""))
        assertFalse(lines[requestBody + 1].contains("hunter2"))
        assertEquals("Content-Type: application/json", lines[responseHeaders + 1])
        assertEquals("""{"ok":true}""", lines[responseBody + 1])
        val noBodies = exporter.text(item, includeBody = false).lines()
        assertTrue("Request Headers:" in noBodies && "Response Headers:" in noBodies)
        assertFalse("Request Body:" in noBodies)
    }

    @Test
    fun textMarksMissingRequestBodyAsEmpty() {
        val lines = exporter.text(event(), includeBody = true).lines()
        assertEquals("(empty)", lines[lines.indexOf("Request Body:") + 1])
    }

    @Test
    fun textDescribesNonHttpEvents() {
        val lines = exporter.text(storage(), includeBody = true).lines()
        assertEquals("KV prefs: theme", lines[0])
        assertTrue("Key: theme" in lines)
        assertTrue("NewValue: dark" in lines)
        assertFalse(lines.any { it.startsWith("OldValue") })
    }

    @Test
    fun textBatchSeparatesEvents() {
        val text = exporter.batch(listOf(event(), storage(), event(method = "POST")), BatchFormat.Text, includeBodies = true)
        val parts = text.split("\n\n${Exporter.SEPARATOR}\n\n")
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("Event 1 of 3\nGET https://api.test"))
        assertTrue(parts[1].startsWith("Event 2 of 3\nKV prefs: theme"))
        assertTrue(parts[2].startsWith("Event 3 of 3\nPOST "))
        assertTrue(parts.all { "Response Body:" in it || it.contains("KV") })
        val single = exporter.batch(listOf(event()), BatchFormat.Text, includeBodies = true)
        assertFalse(Exporter.SEPARATOR in single)
        assertFalse(single.startsWith("Event"))
        val head = exporter.batch(listOf(event(), storage()), BatchFormat.Text, includeBodies = true, total = 65)
        assertTrue(head.startsWith("Event 1 of 65\n"))
        assertTrue("Event 2 of 65\n" in head)
    }

    @Test
    fun expandedPreviewIsCappedWithANote() {
        assertEquals("short", limitPreview("short"))
        val long = limitPreview("x".repeat(300_000))
        assertTrue(long.length < 210_000)
        assertTrue(long.endsWith("Share the file to get everything."))
    }

    @Test
    fun textIsTheFirstFormatInBothSheets() {
        assertEquals(BatchFormat.Text, BatchFormat.entries.first())
        assertEquals(EventFormat.Text, EventFormat.entries.first())
    }

    @Test
    fun countsMaskedHeaders() {
        assertEquals(1, exporter.maskedHeaderCount(event()))
        assertEquals(0, exporter.maskedHeaderCount(storage()))
    }

    @Test
    fun unmaskedExporterKeepsSecretsInEveryFormat() {
        val raw = Exporter(zone = TimeZone.UTC, masks = false)
        val item = event(method = "POST", requestBody = """{"password":"hunter2","name":"a"}""")
        val outputs =
            listOf(
                raw.text(item, includeBody = true),
                raw.curl(item, includeBody = true),
                raw.batch(listOf(item), BatchFormat.Json, includeBodies = true),
                raw.batch(listOf(item), BatchFormat.Har, includeBodies = true),
            )
        outputs.forEach { output ->
            assertTrue("abcdefghijklmnop" in output, output)
            assertTrue("abcdef123456" in output, output)
            assertTrue("hunter2" in output, output)
            assertFalse("••••" in output, output)
        }
    }

    @Test
    fun labelsSayWhetherExportsAreMasked() {
        assertEquals("Copied (masked)", copiedLabel("Copied", masks = true))
        assertEquals("Copied", copiedLabel("Copied", masks = false))
        assertEquals("Already masked", bodiesSubtitle(0, masks = true))
        assertEquals("Not masked", bodiesSubtitle(0, masks = false))
        assertTrue(bodiesSubtitle(2048, masks = false).endsWith("of request and response, not masked"))
    }
}
