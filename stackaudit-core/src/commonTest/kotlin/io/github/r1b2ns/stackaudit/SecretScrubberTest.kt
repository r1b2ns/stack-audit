package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecretScrubberTest {
    private val scrubber = SecretScrubber(SecretRules.Default)

    @Test
    fun masksDefaultHeadersKeepingSchemeAndTail() {
        val result =
            scrubber.headers(
                listOf(Header("Authorization", "Bearer abcdefghijkl1234"), Header("Accept", "application/json")),
            )
        assertEquals(Header("Authorization", "Bearer ••••1234"), result[0])
        assertEquals(Header("Accept", "application/json"), result[1])
    }

    @Test
    fun shortValuesAreFullyMasked() {
        assertEquals("••••", SecretScrubber.mask("short"))
        assertEquals("", SecretScrubber.mask(""))
    }

    @Test
    fun maskIsIdempotent() {
        val once = SecretScrubber.mask("abcdefghijkl1234")
        assertEquals(once, SecretScrubber.mask(once))
    }

    @Test
    fun customRulesAddAndAllowNames() {
        val rules =
            SecretRules.build {
                header("X-Session")
                jsonField("cpf")
                allow("Cookie")
            }
        val custom = SecretScrubber(rules)
        val headers = custom.headers(listOf(Header("x-session", "abc"), Header("Cookie", "a=b")))
        assertEquals("••••", headers[0].value)
        assertEquals("a=b", headers[1].value)
        assertEquals("""{"cpf":"••••"}""", custom.json("""{"cpf":"12345"}"""))
    }

    @Test
    fun matcherFlagsExtraNames() {
        val custom = SecretScrubber(SecretRules.build { matching { it.startsWith("x-secret") } })
        assertEquals("••••", custom.headers(listOf(Header("X-Secret-Id", "1")))[0].value)
    }

    @Test
    fun masksNestedJsonFields() {
        val json = """{"user":{"name":"ana","password":"hunter2"},"items":[{"access_token":"abcdefghijklmnop"}]}"""
        val masked = scrubber.json(json)
        assertTrue("\"name\":\"ana\"" in masked)
        assertTrue("\"password\":\"••••\"" in masked)
        assertTrue("\"access_token\":\"••••mnop\"" in masked)
    }

    @Test
    fun jsonWithoutSecretsIsReturnedUntouched() {
        val json = """{ "name" : "ana" }"""
        assertSame(json, scrubber.json(json))
    }

    @Test
    fun invalidJsonIsReturnedUntouched() {
        assertEquals("{not json", scrubber.json("{not json"))
    }

    @Test
    fun masksFormAndQuery() {
        assertEquals("user=ana&password=••••", scrubber.form("user=ana&password=hunter2"))
        assertEquals(
            "https://api.test/login?token=••••&page=1#top",
            scrubber.url("https://api.test/login?token=xyz&page=1#top"),
        )
        assertEquals("https://api.test/a", scrubber.url("https://api.test/a"))
    }

    @Test
    fun bodyDispatchesOnContentType() {
        assertEquals("password=••••", scrubber.body("password=abc", "application/x-www-form-urlencoded"))
        assertEquals("""{"token":"••••"}""", scrubber.body("""{"token":"abc"}""", "application/json; charset=utf-8"))
        assertEquals("""{"token":"••••"}""", scrubber.body("""{"token":"abc"}""", null))
        assertEquals("token=abc", scrubber.body("token=abc", "text/plain"))
    }
}
