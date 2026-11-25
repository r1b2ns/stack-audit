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
    fun stillValidatesConfiguration() {
        assertFailsWith<IllegalArgumentException> { StackAudit.start { secrets = Secrets.ShowAfterHandshake() } }
    }
}
