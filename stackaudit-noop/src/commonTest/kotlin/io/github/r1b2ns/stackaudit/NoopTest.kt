package io.github.r1b2ns.stackaudit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoopTest {
    @Test
    fun keepsTheSameApiWithoutRecording() =
        runTest {
            StackAudit.start {
                secrets = Secrets.Mask { header("X-Session") }
                access = Access.Hidden
            }
            StackAudit.record(HttpExchange("t", "GET", "https://a.test", emptyList(), null, null, 200, emptyList(), null, null, 0, 0))
            assertFalse(StackAudit.isStarted)
            assertFalse(StackAudit.isCapturing)
            assertFalse(StackAudit.show())
            assertTrue(StackAudit.events().first().isEmpty())
            assertTrue(StackAudit.verify().valid)
        }

    @Test
    fun flagsReturnRemoteOrDefaultAndAnalyticsForwards() {
        val flags = StackAudit.flags.wrap({ key -> if (key == "on") FlagValue.Bool(true) else null })
        StackAudit.flags.override("off", FlagValue.Bool(true))
        assertTrue(flags.bool("on", false))
        assertFalse(flags.bool("off", false))
        var tracked = ""
        StackAudit.analytics.wrap({ name, _ -> tracked = name }, "firebase").track("purchase", emptyMap())
        assertTrue(tracked == "purchase")
    }

    @Test
    fun stillValidatesConfiguration() {
        assertFailsWith<IllegalArgumentException> { StackAudit.start { secrets = Secrets.ShowAfterHandshake() } }
    }
}
