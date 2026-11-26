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
    fun masksHeadersNamedAfterSecretFields() {
        val result =
            scrubber.headers(
                listOf(
                    Header("access_token", "7dd29878-e58a-47ad-aeac-373f99965ddd"),
                    Header("Access-Token", "7dd29878-e58a-47ad-aeac-373f99965ddd"),
                    Header("X-Request-Id", "7dd29878-e58a-47ad-aeac-373f99965ddd"),
                    Header("client_secret", "s3cr3t-value-0001"),
                    Header("token", "abc"),
                ),
            )
        assertEquals("••••5ddd", result[0].value)
        assertEquals("••••5ddd", result[1].value)
        assertEquals("7dd29878-e58a-47ad-aeac-373f99965ddd", result[2].value)
        assertEquals("••••0001", result[3].value)
        assertEquals("••••", result[4].value)
    }

    @Test
    fun allowedFieldNameIsNotMaskedAsHeader() {
        val custom = SecretScrubber(SecretRules.build { allow("access_token") })
        val header = Header("access_token", "7dd29878-e58a-47ad-aeac-373f99965ddd")
        assertEquals(header, custom.headers(listOf(header))[0])
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
    fun masksSecretHeaderNamesInJsonBodies() {
        assertEquals(
            """{"Authorization":"••••1234","Cookie":"••••1234","X-Api-Key":"••••1234","Proxy-Authorization":"••••1234","set_cookie":"••••1234"}""",
            scrubber.json(
                """{"Authorization":"Bearer abcdefghijkl1234","Cookie":"session=abcd1234","X-Api-Key":"key-abcdef-1234","Proxy-Authorization":"Basic abcdef1234","set_cookie":"id=abcdefgh1234"}""",
            ),
        )
    }

    @Test
    fun masksEchoedRequestHeadersInResponseBody() {
        val echoed =
            """{"args":{},"headers":{"Accept":"*/*","Authorization":"Bearer abcdefghijkl1234","Host":"httpbin.org"},"url":"https://httpbin.org/post"}"""
        assertEquals(
            """{"args":{},"headers":{"Accept":"*/*","Authorization":"••••1234","Host":"httpbin.org"},"url":"https://httpbin.org/post"}""",
            scrubber.body(echoed, "application/json"),
        )
    }

    @Test
    fun masksSecretHeaderNamesInFormAndQuery() {
        assertEquals("user=ana&cookie=••••", scrubber.form("user=ana&cookie=a%3Db"))
        assertEquals(
            "https://api.test/a?x_api_key=••••1234&page=1",
            scrubber.url("https://api.test/a?x_api_key=key-abcdef-1234&page=1"),
        )
    }

    @Test
    fun allowedHeaderNameIsNotMaskedInBodies() {
        val custom = SecretScrubber(SecretRules.build { allow("Cookie") })
        assertEquals("""{"cookie":"a=b"}""", custom.json("""{"cookie":"a=b"}"""))
        assertEquals("""{"authorization":"••••1234"}""", custom.json("""{"authorization":"Bearer abcdefghijkl1234"}"""))
    }

    @Test
    fun customHeaderNameIsMaskedInBodies() {
        val custom = SecretScrubber(SecretRules.build { header("X-Session") })
        assertEquals("""{"x-session":"••••"}""", custom.json("""{"x-session":"abc"}"""))
    }

    @Test
    fun bodyDispatchesOnContentType() {
        assertEquals("password=••••", scrubber.body("password=abc", "application/x-www-form-urlencoded"))
        assertEquals("""{"token":"••••"}""", scrubber.body("""{"token":"abc"}""", "application/json; charset=utf-8"))
        assertEquals("""{"token":"••••"}""", scrubber.body("""{"token":"abc"}""", null))
        assertEquals("token=abc", scrubber.body("token=abc", "text/plain"))
    }
}
