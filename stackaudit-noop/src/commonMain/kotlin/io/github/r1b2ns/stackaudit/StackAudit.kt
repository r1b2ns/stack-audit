package io.github.r1b2ns.stackaudit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

public object StackAudit :
    HttpRecorder,
    WebSocketRecorder,
    StorageRecorder,
    DatabaseRecorder,
    AnalyticsRecorder,
    FlagRecorder {
    public val isStarted: Boolean get() = false

    public val analytics: StackAuditAnalytics = StackAuditAnalytics()

    public val flags: StackAuditFlags = StackAuditFlags()

    override val isCapturingAnalytics: Boolean get() = false

    override val isCapturingFlags: Boolean get() = false

    public val config: StackAuditConfig? get() = null

    override val isCapturing: Boolean get() = false

    override val isCapturingWebSockets: Boolean get() = false

    override val isCapturingStorage: Boolean get() = false

    override val isCapturingDatabase: Boolean get() = false

    override val maxBodySize: Long get() = 0

    public fun start(configure: StackAuditConfig.Builder.() -> Unit = {}) {
        StackAuditConfig.build(configure)
    }

    public fun start(config: StackAuditConfig) {
        config.hashCode()
    }

    public suspend fun stop() {}

    public fun registerViewer(viewer: AuditViewer) {
        viewer.hashCode()
    }

    public fun show(): Boolean = false

    override fun record(exchange: HttpExchange) {}

    override fun record(frame: WebSocketFrame) {}

    override fun record(change: StorageChange) {}

    override fun record(statement: DatabaseStatement) {}

    override fun record(call: AnalyticsCall) {}

    override fun record(
        flag: FlagPayload,
        timestamp: Long,
    ) {}

    public fun events(
        category: Category? = null,
        query: String? = null,
        limit: Long = 500,
    ): Flow<List<AuditEventSummary>> = flowOf(emptyList())

    public val session: StateFlow<AuditSession?> = MutableStateFlow(null)

    public val deviceId: String? get() = null

    public fun allows(scope: Scope): Boolean = false.also { scope.hashCode() }

    public fun handshake(token: String): HandshakeResult = HandshakeResult.Rejected("StackAudit is disabled").also { token.hashCode() }

    public fun handleLink(url: String): Boolean = false.also { url.hashCode() }

    public fun endSession() {}

    public suspend fun event(
        id: String,
        revealSecrets: Boolean = false,
    ): AuditEvent? = null.also { id.hashCode() + revealSecrets.hashCode() }

    public suspend fun body(ref: BodyRef): ByteArray? = null

    public suspend fun exportHar(includeSecrets: Boolean = false): String =
        """{"log":{"version":"1.2","creator":{"name":"StackAudit","version":"0.1.0"},"entries":[]}}"""

    public suspend fun exportJsonl(includeSecrets: Boolean = false): String = ""

    public suspend fun clear() {}

    public suspend fun purge() {}

    public suspend fun verify(): ChainReport = ChainReport(true, 0, null)

    public suspend fun flush() {}
}
