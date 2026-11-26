package io.github.r1b2ns.stackaudit.ui

import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.FlagValue
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationTest {
    private val base = 1_700_000_000_000
    private val day = 86_400_000L

    private fun http(
        id: String,
        title: String,
        host: String = "a.test",
        method: String = "GET",
        status: Int? = 200,
        timestamp: Long = base,
        sent: Long? = 10,
        received: Long? = 100,
    ) = AuditEventSummary(id, timestamp, 12, Category.Http, "okhttp", title, method, host, status, sent, received)

    private fun other(
        id: String,
        category: Category,
        title: String,
        timestamp: Long = base,
    ) = AuditEventSummary(id, timestamp, null, category, "app", title, null, null, null)

    @Test
    fun formatsBytesInDecimalUnits() {
        assertEquals("0 bytes", Presentation.bytes(0))
        assertEquals("610 bytes", Presentation.bytes(610))
        assertEquals("2 KB", Presentation.bytes(1_536))
        assertEquals("999 KB", Presentation.bytes(999_000))
        assertEquals("1.8 MB", Presentation.bytes(1_800_000))
        assertEquals("2 MB", Presentation.bytes(2_000_000))
        assertEquals("3.25 GB", Presentation.bytes(3_250_000_000))
    }

    @Test
    fun formatsTimesInEnglish() {
        assertEquals("10:13 PM", Presentation.shortTime(base, TimeZone.UTC))
        assertEquals("12:00 AM", Presentation.shortTime(0, TimeZone.UTC))
        assertEquals("Nov 14, 2023", Presentation.shortDate(base, TimeZone.UTC))
        assertEquals("Nov 14, 2023 at 10:13:20.000 PM", Presentation.dateTime(base, TimeZone.UTC))
        assertEquals("Nov 14, 2023 at 10:13:20 PM", Presentation.mediumDateTime(base, TimeZone.UTC))
        assertEquals("", Presentation.duration(null))
        assertEquals("120 ms", Presentation.duration(120))
    }

    @Test
    fun titlesDaysRelativeToNow() {
        assertEquals("Today", Presentation.dayTitle(base, base, TimeZone.UTC))
        assertEquals("Yesterday", Presentation.dayTitle(base - day, base, TimeZone.UTC))
        assertEquals("Saturday, November 11", Presentation.dayTitle(base - 3 * day, base, TimeZone.UTC))
    }

    @Test
    fun groupsEventsByDayNewestFirst() {
        val events = listOf(http("1", "GET a.test/ 200"), http("2", "GET a.test/ 200", timestamp = base - day), http("3", "GET a.test/ 200"))
        val groups = Presentation.groupByDay(events, base, TimeZone.UTC)
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.title })
        assertEquals(listOf("1", "3"), groups.first().events.map { it.id })
    }

    @Test
    fun splitsHttpTitleIntoHostAndPath() {
        val event = http("1", "GET a.test/v1/users?x=1 200")
        assertEquals("a.test", Presentation.headline(event))
        assertEquals("/v1/users?x=1", Presentation.path(event))
        assertEquals("/", Presentation.path(http("2", "GET a.test 200")))
        assertEquals("GET", Presentation.method(event))
        val storage = other("3", Category.Storage, "prefs: token")
        assertEquals("KV", Presentation.method(storage))
        assertEquals("prefs: token", Presentation.headline(storage))
        assertEquals("app", Presentation.path(storage))
    }

    @Test
    fun describesStatus() {
        assertEquals("200", Presentation.statusText(http("1", "t", status = 200)))
        assertEquals("404 Not Found", Presentation.statusText(http("1", "t", status = 404)))
        assertEquals("Failed", Presentation.statusText(http("1", "t", status = null)))
        assertEquals("200 OK", Presentation.statusTitle(200, null))
        assertEquals("Failed", Presentation.statusTitle(null, "timeout"))
        assertEquals("Delivered", Presentation.statusText(other("2", Category.WebSocket, "WS ↑ a.test/ws")))
        assertEquals("Failed", Presentation.statusText(other("2", Category.WebSocket, "WS failed a.test/ws")))
    }

    @Test
    fun decidesSuccessPerCategory() {
        assertTrue(Presentation.isSuccess(http("1", "t", status = 304)))
        assertFalse(Presentation.isSuccess(http("1", "t", status = 500)))
        assertFalse(Presentation.isSuccess(http("1", "t", status = null)))
        assertFalse(Presentation.isSuccess(other("2", Category.Analytics, "firebase: x (invalid)")))
        assertFalse(Presentation.isSuccess(other("2", Category.Analytics, "firebase: x (not in plan)")))
        assertTrue(Presentation.isSuccess(other("2", Category.Analytics, "firebase: x")))
        assertTrue(Presentation.isSuccess(other("3", Category.Storage, "prefs")))
    }

    @Test
    fun tonesAndDirectionsFollowCategory() {
        assertEquals(Tone.Blue, Presentation.categoryTone(Category.Http))
        assertEquals(Tone.Green, Presentation.categoryTone(Category.Database))
        assertEquals(Tone.Purple, Presentation.categoryTone(Category.Analytics))
        assertEquals(Tone.Orange, Presentation.categoryTone(Category.Flag))
        assertEquals(Tone.Gray, Presentation.categoryTone(Category.Audit))
        assertTrue(Presentation.isOutbound(Category.Http))
        assertFalse(Presentation.isOutbound(Category.Storage))
    }

    @Test
    fun filtersByCategoryAndErrors() {
        assertEquals(
            listOf("All", "Network", "WebSocket", "Storage", "Database", "Analytics", "Flags", "Log", "Errors"),
            Filter.entries.map { it.title },
        )
        val ok = http("1", "t")
        val failed = http("2", "t", status = 503)
        val storage = other("3", Category.Storage, "prefs")
        val events = listOf(ok, failed, storage)
        assertEquals(events, events.filter(Filter.All::matches))
        assertEquals(listOf(ok, failed), events.filter(Filter.Network::matches))
        assertEquals(listOf(failed), events.filter(Filter.Errors::matches))
        assertEquals(listOf(storage), events.filter(Filter.Storage::matches))
    }

    @Test
    fun searchesHostAndPath() {
        val event = http("1", "GET api.test/v1/users 200", host = "api.test")
        assertTrue(Presentation.matchesSearch(event, ""))
        assertTrue(Presentation.matchesSearch(event, "API"))
        assertTrue(Presentation.matchesSearch(event, "/v1/users"))
        assertFalse(Presentation.matchesSearch(event, "orders"))
    }

    @Test
    fun summarisesTodayAndDestinations() {
        val events =
            listOf(
                http("1", "t", host = "a.test", sent = 100, received = 1_000),
                http("2", "t", host = "b.test", sent = 50, received = null),
                http("3", "t", host = "a.test", sent = 1, received = 2, timestamp = base - day),
                other("4", Category.Storage, "prefs"),
            )
        assertEquals(TodayStats(requests = 2, destinations = 2, sent = 150, received = 1_000), Presentation.today(events, base, TimeZone.UTC))
        val destinations = Presentation.destinations(events)
        assertEquals(listOf("a.test", "b.test"), destinations.map { it.host })
        assertEquals(Destination("a.test", 2, 101, 1_002, base), destinations.first())
    }

    @Test
    fun parsesHostAndPathFromUrl() {
        assertEquals("a.test" to "/v1?q=1", Presentation.hostAndPath("https://a.test/v1?q=1"))
        assertEquals("a.test" to "/", Presentation.hostAndPath("https://a.test"))
    }

    @Test
    fun describesBodies() {
        val json = Presentation.body(BodyRef("h", 7, "application/json", stored = true, text = true), """{"a":1}""".encodeToByteArray())
        assertTrue(json is BodyContent.Text && "\"a\": 1" in json.text)
        assertEquals(BodyContent.Note("No body."), Presentation.body(BodyRef(null, 0, null, stored = true, text = true), null))
        assertEquals(
            BodyContent.Note("Not stored: 2 KB, above maxBodySize."),
            Presentation.body(BodyRef(null, 2_048, null, stored = false, text = true), null),
        )
        assertEquals(BodyContent.Note("Binary content (10 bytes)."), Presentation.body(BodyRef("h", 10, "image/png", stored = true, text = false), ByteArray(10)))
        assertEquals(
            BodyContent.Note("Not available: the body couldn't be read on this device."),
            Presentation.body(BodyRef("h", 825, "application/json", stored = true, text = true), ByteArray(0)),
        )
        assertEquals("2 KB · JSON", Presentation.bodyMeta(BodyRef("h", 2_048, "application/json; charset=utf-8", stored = true, text = true)))
    }

    @Test
    fun parsesOverridesByType() {
        assertEquals(FlagValue.Bool(true), Presentation.parseOverride(FlagValue.Bool(false), " TRUE "))
        assertEquals(null, Presentation.parseOverride(FlagValue.Bool(false), "yes"))
        assertEquals(FlagValue.Number(2.5), Presentation.parseOverride(FlagValue.Number(1.0), "2.5"))
        assertEquals(null, Presentation.parseOverride(FlagValue.Number(1.0), "abc"))
        assertEquals(FlagValue.Text("x"), Presentation.parseOverride(FlagValue.Text(""), "x"))
    }
}
