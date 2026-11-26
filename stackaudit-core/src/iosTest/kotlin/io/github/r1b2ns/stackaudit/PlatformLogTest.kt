package io.github.r1b2ns.stackaudit

import kotlin.test.Test

class PlatformLogTest {
    @Test
    fun logsMessagesContainingFormatSpecifiers() {
        platformLog("StackAudit: already started, ignoring start().")
        platformLog("100% done %@ %s %d")
    }
}
