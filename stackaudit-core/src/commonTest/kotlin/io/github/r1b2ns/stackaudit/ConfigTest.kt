package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class ConfigTest {
    @Test
    fun defaultsAreSafe() {
        val config = StackAuditConfig.build()
        assertIs<Secrets.Mask>(config.secrets)
        assertEquals(Access.Open, config.access)
        assertEquals(7.days, config.retention)
        assertEquals(1024L * 1024L, config.maxBodySize)
        assertEquals(100L * 1024L * 1024L, config.maxStorage)
        assertEquals(Category.entries.toSet(), config.categories)
    }

    @Test
    fun showAfterHandshakeRequiresHandshakeAccess() {
        val error = assertFailsWith<IllegalArgumentException> { StackAuditConfig.build { secrets = Secrets.ShowAfterHandshake() } }
        assertTrue("Access.Handshake" in error.message.orEmpty())
        StackAuditConfig.build {
            secrets = Secrets.ShowAfterHandshake()
            access = Access.Handshake(publicKey = "key")
        }
    }

    @Test
    fun rejectsInvalidLimits() {
        assertFailsWith<IllegalArgumentException> { StackAuditConfig.build { maxStorage = 0 } }
        assertFailsWith<IllegalArgumentException> { StackAuditConfig.build { maxBodySize = -1 } }
        assertFailsWith<IllegalArgumentException> { StackAuditConfig.build { queueCapacity = 0 } }
    }

    @Test
    fun showModeDoesNotMaskOnWrite() {
        assertTrue(Secrets.Mask().masksOnWrite)
        assertTrue(!Secrets.Show.masksOnWrite)
    }
}
