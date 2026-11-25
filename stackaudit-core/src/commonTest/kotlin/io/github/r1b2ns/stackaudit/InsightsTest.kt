package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsightsTest {
    private class Recorder :
        AnalyticsRecorder,
        FlagRecorder {
        override val isCapturingAnalytics = true
        override val isCapturingFlags = true
        val calls = mutableListOf<AnalyticsCall>()
        val flags = mutableListOf<FlagPayload>()

        override fun record(call: AnalyticsCall) {
            calls += call
        }

        override fun record(
            flag: FlagPayload,
            timestamp: Long,
        ) {
            flags += flag
        }
    }

    private class MemoryStore : OverrideStore {
        val values = mutableMapOf<String, FlagValue>()

        override fun load() = values.toMap()

        override fun save(
            key: String,
            value: FlagValue?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val plan =
        TrackingPlan.fromJson(
            """{"strict":true,"events":{"purchase":{"required":["value","currency"],"optional":["coupon"],"types":{"value":"number","currency":"string"}}}}""",
        )

    @Test
    fun validatesAgainstTheTrackingPlan() {
        assertEquals(ValidationStatus.Valid, plan.validate("purchase", mapOf("value" to 9.9, "currency" to "BRL")).first)
        assertEquals(ValidationStatus.UnknownEvent, plan.validate("login", emptyMap()).first)
        val (status, issues) = plan.validate("purchase", mapOf("value" to "9.9", "extra" to 1))
        assertEquals(ValidationStatus.Invalid, status)
        assertEquals(
            listOf("missing required param \"currency\"", "unexpected param \"extra\"", "param \"value\" should be number"),
            issues,
        )
    }

    @Test
    fun wrapRecordsAndForwards() {
        val recorder = Recorder()
        val analytics = StackAuditAnalytics({ recorder }, { 5L })
        analytics.plan = plan
        val forwarded = mutableListOf<String>()
        analytics.wrap({ name, _ -> forwarded += name }, "firebase").track("purchase", mapOf("value" to 1, "currency" to "BRL"))
        assertEquals(listOf("purchase"), forwarded)
        val call = recorder.calls.single()
        assertEquals("firebase", call.provider)
        assertEquals(ValidationStatus.Valid, call.validation)
        assertEquals(5L, call.timestamp)
    }

    @Test
    fun overridesWinOverRemoteAndDefault() {
        val recorder = Recorder()
        val flags = StackAuditFlags({ recorder }, { 0L })
        val remote = mutableMapOf<String, FlagValue>("checkout" to FlagValue.Bool(true), "limit" to FlagValue.Text("wrong type"))
        val audited = flags.wrap({ remote[it] }, "remote_config")
        assertTrue(audited.bool("checkout", false))
        assertEquals(10.0, audited.number("limit", 10.0))
        flags.override("checkout", FlagValue.Bool(false))
        assertEquals(false, audited.bool("checkout", true))
        assertEquals(FlagSource.Override, flags.states.value["checkout"]?.source)
        flags.clearOverride("checkout")
        assertTrue(audited.bool("checkout", false))
        assertEquals(
            listOf(FlagSource.Remote, FlagSource.Default, FlagSource.Override, FlagSource.Remote),
            recorder.flags.map { it.source },
        )
        assertEquals("stackaudit", recorder.flags[2].changedBy)
    }

    @Test
    fun repeatedReadsAreRecordedOnce() {
        val recorder = Recorder()
        val audited = StackAuditFlags({ recorder }, { 0L }).wrap({ null })
        repeat(3) { audited.string("greeting", "hi") }
        assertEquals(1, recorder.flags.size)
    }

    @Test
    fun overridesPersistThroughTheStore() {
        val store = MemoryStore()
        val first = StackAuditFlags({ null }, { 0L })
        first.attach(store)
        first.override("dark_mode", FlagValue.Bool(true))
        val second = StackAuditFlags({ null }, { 0L })
        second.attach(store)
        assertTrue(second.wrap({ null }).bool("dark_mode", false))
        second.clearOverrides()
        assertTrue(store.values.isEmpty())
    }
}
