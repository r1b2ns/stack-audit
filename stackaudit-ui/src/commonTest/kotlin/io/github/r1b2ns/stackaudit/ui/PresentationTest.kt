package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.Category
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresentationTest {
    @Test
    fun tonesFollowStatus() {
        assertEquals(Tone.Success, Presentation.tone(Category.Http, 204))
        assertEquals(Tone.Redirect, Presentation.tone(Category.Http, 301))
        assertEquals(Tone.ClientError, Presentation.tone(Category.Http, 404))
        assertEquals(Tone.ServerError, Presentation.tone(Category.Http, 503))
        assertEquals(Tone.Failed, Presentation.tone(Category.Http, null))
        assertEquals(Tone.Neutral, Presentation.tone(Category.Audit, null))
    }

    @Test
    fun formatsTimeDurationAndSize() {
        assertEquals("22:13:20", Presentation.time(1_700_000_000_000, TimeZone.UTC))
        assertEquals("", Presentation.duration(null))
        assertEquals("120 ms", Presentation.duration(120))
        assertEquals("2.5 s", Presentation.duration(2_540))
        assertEquals("512 B", Presentation.size(512))
        assertEquals("1.5 KB", Presentation.size(1_536))
        assertEquals("2.0 MB", Presentation.size(2L * 1024 * 1024))
    }

    @Test
    fun prettyPrintsJsonBodies() {
        val ref = BodyRef("h", 9, "application/json", stored = true, text = true)
        val body = Presentation.body(ref, """{"a":1}""".encodeToByteArray())
        assertTrue(body.contains("\n"))
        assertTrue(body.contains("\"a\": 1"))
    }

    @Test
    fun explainsMissingBodies() {
        assertTrue(Presentation.body(BodyRef(null, 2_048, null, stored = false, text = true), null).startsWith("Not stored"))
        assertEquals("Body no longer available", Presentation.body(BodyRef("h", 1, null, stored = true, text = true), null))
        assertEquals("Binary content, 10 B", Presentation.body(BodyRef("h", 10, "image/png", stored = true, text = false), ByteArray(10)))
        assertEquals("plain", Presentation.body(BodyRef("h", 5, "text/plain", stored = true, text = true), "plain".encodeToByteArray()))
    }

    @Test
    fun labelsFilters() {
        assertEquals(listOf("All", "Network", "Log"), Presentation.filters.map(Presentation::filterLabel))
    }
}
