package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilTest {
    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test
    fun parsesUrlParts() {
        val parts = UrlParts.parse("https://user@api.test:8443/v1/items?page=2#frag")
        assertEquals("https", parts.scheme)
        assertEquals("api.test:8443", parts.host)
        assertEquals("/v1/items", parts.path)
        assertEquals("page=2", parts.query)
        val bare = UrlParts.parse("https://api.test")
        assertEquals("/", bare.path)
        assertNull(bare.query)
    }

    @Test
    fun detectsTextBodies() {
        assertTrue(BodyKind.isText("application/json", byteArrayOf()))
        assertFalse(BodyKind.isText("image/png", byteArrayOf()))
        assertTrue(BodyKind.isText(null, "hello".encodeToByteArray()))
        assertFalse(BodyKind.isText(null, byteArrayOf(0, 1, 2, -1)))
    }

    @Test
    fun buildsCurl() {
        val payload =
            HttpPayload(
                method = "POST",
                url = "https://api.test/a",
                requestHeaders = listOf(Header("Content-Type", "application/json")),
                requestBody = null,
                status = 200,
                responseHeaders = emptyList(),
                responseBody = null,
            )
        assertEquals(
            "curl -X POST 'https://api.test/a' -H 'Content-Type: application/json' --data-raw '{\"a\":\"it'\\''s\"}'",
            Curl.from(payload, """{"a":"it's"}"""),
        )
    }
}
