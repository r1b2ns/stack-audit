package io.github.r1b2ns.stackaudit.ktor

import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.StackAudit
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

public class StackAuditKtorConfig {
    public var source: String = "ktor"
    public var recorder: HttpRecorder = StackAudit
}

public val StackAuditKtor: ClientPlugin<StackAuditKtorConfig> = createClientPlugin("StackAudit", ::StackAuditKtorConfig) {}
