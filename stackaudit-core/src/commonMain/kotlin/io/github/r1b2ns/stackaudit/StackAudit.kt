package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

public object StackAudit :
    HttpRecorder,
    WebSocketRecorder,
    StorageRecorder,
    DatabaseRecorder,
    AnalyticsRecorder,
    FlagRecorder {
    private val engine = MutableStateFlow<AuditEngine?>(null)
    private var viewer: AuditViewer? = null

    public val isStarted: Boolean get() = engine.value != null

    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    public val analytics: StackAuditAnalytics = StackAuditAnalytics({ this.takeIf { isStarted } }, clock)

    public val flags: StackAuditFlags = StackAuditFlags({ this.takeIf { isStarted } }, clock)

    private val sessionState = MutableStateFlow<AuditSession?>(null)

    public val session: StateFlow<AuditSession?> = sessionState.asStateFlow()

    private var verifier: HandshakeVerifier? = null

    private var timeSource: () -> Long = clock

    public val deviceId: String? get() = engine.value?.deviceId

    override val isCapturingAnalytics: Boolean get() = engine.value?.config?.categories?.contains(Category.Analytics) == true

    override val isCapturingFlags: Boolean get() = engine.value?.config?.categories?.contains(Category.Flag) == true

    public val config: StackAuditConfig? get() = engine.value?.config

    override val isCapturing: Boolean get() = engine.value?.config?.categories?.contains(Category.Http) == true

    override val isCapturingWebSockets: Boolean get() = engine.value?.config?.categories?.contains(Category.WebSocket) == true

    override val isCapturingStorage: Boolean get() = engine.value?.config?.categories?.contains(Category.Storage) == true

    override val isCapturingDatabase: Boolean get() = engine.value?.config?.categories?.contains(Category.Database) == true

    override val maxBodySize: Long get() = engine.value?.config?.maxBodySize ?: 0

    public fun start(configure: StackAuditConfig.Builder.() -> Unit = {}) {
        start(StackAuditConfig.build(configure))
    }

    public fun start(config: StackAuditConfig) {
        if (isStarted) {
            platformLog("StackAudit: already started, ignoring start().")
            return
        }
        start(config, createPlatformDriver(), platformCipher(), Dispatchers.IO) { Clock.System.now().toEpochMilliseconds() }
    }

    internal fun start(
        config: StackAuditConfig,
        driver: SqlDriver,
        cipher: BlobCipher,
        context: CoroutineContext,
        now: () -> Long,
    ) {
        val access = config.access
        val key = (access as? Access.Handshake)?.let { HandshakeVerifier.parsePublicKey(it.publicKey) }
        val created = AuditEngine(config, driver, cipher, context, now)
        if (!engine.compareAndSet(null, created)) return
        verifier =
            if (access is Access.Handshake && key != null) {
                HandshakeVerifier(key, access.appId ?: platformAppId(), { created.deviceId }, access.sessionTtl, created.nonceStore, now)
            } else {
                null
            }
        sessionState.value = null
        timeSource = now
        if (config.secrets is Secrets.Show && !isDebuggableBuild()) {
            platformLog("StackAudit: Secrets.Show is active in a release build. Secrets are stored in clear text.")
        }
        flags.attach(created.overrideStore)
        created.recordMeta(MetaKind.Started, "StackAudit started")
    }

    public suspend fun stop() {
        val current = engine.value ?: return
        if (engine.compareAndSet(current, null)) {
            flags.attach(null)
            verifier = null
            sessionState.value = null
            current.shutdown()
        }
    }

    public fun registerViewer(viewer: AuditViewer) {
        this.viewer = viewer
    }

    public fun show(): Boolean {
        val current = engine.value
        if (current == null) {
            platformLog("StackAudit: call StackAudit.start() before show().")
            return false
        }
        return when (current.config.access) {
            Access.Hidden -> {
                platformLog("StackAudit: access is Hidden, the log is only available through export.")
                false
            }
            is Access.Handshake, Access.Open -> {
                if (!allows(Scope.Read)) {
                    platformLog("StackAudit: access requires a signed handshake token.")
                    return false
                }
                val target = viewer
                if (target == null) {
                    platformLog("StackAudit: add the stackaudit-ui dependency to show the log.")
                    false
                } else {
                    target.present()
                    true
                }
            }
        }
    }

    public fun allows(scope: Scope): Boolean {
        val config = engine.value?.config ?: return false
        return when (config.access) {
            Access.Hidden -> false
            Access.Open -> scope != Scope.ReadSecrets || config.secrets is Secrets.Show
            is Access.Handshake -> sessionState.value?.allows(scope, timeSource()) == true
        }
    }

    public fun handshake(token: String): HandshakeResult {
        val current = engine.value ?: return HandshakeResult.Rejected("StackAudit is not started")
        val check = verifier ?: return HandshakeResult.Rejected("access is not Access.Handshake")
        val result = check.verify(token)
        when (result) {
            is HandshakeResult.Accepted -> {
                sessionState.value = result.session
                current.recordMeta(MetaKind.HandshakeAccepted, "handshake accepted: " + result.session.scopes.joinToString { it.wire })
            }
            is HandshakeResult.Rejected -> current.recordMeta(MetaKind.HandshakeRejected, "handshake rejected: ${result.reason}")
        }
        return result
    }

    public fun handleLink(url: String): Boolean {
        if (!url.contains("stackaudit/open")) return false
        val token =
            UrlParts.parse(url).query
                ?.split('&')
                ?.firstOrNull { it.startsWith("token=") }
                ?.substringAfter("token=")
                ?: return false
        val result = handshake(token)
        return result is HandshakeResult.Accepted && show()
    }

    public fun endSession() {
        sessionState.value = null
    }

    override fun record(exchange: HttpExchange) {
        engine.value?.recordHttp(exchange)
    }

    override fun record(frame: WebSocketFrame) {
        engine.value?.recordWebSocket(frame)
    }

    override fun record(change: StorageChange) {
        engine.value?.recordStorage(change)
    }

    override fun record(statement: DatabaseStatement) {
        engine.value?.recordDatabase(statement)
    }

    override fun record(call: AnalyticsCall) {
        engine.value?.recordAnalytics(call)
    }

    override fun record(
        flag: FlagPayload,
        timestamp: Long,
    ) {
        engine.value?.recordFlag(flag, timestamp)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    public fun events(
        category: Category? = null,
        query: String? = null,
        limit: Long = 500,
    ): Flow<List<AuditEventSummary>> = engine.flatMapLatest { it?.summaries(category, query, limit) ?: flowOf(emptyList()) }

    public suspend fun event(
        id: String,
        revealSecrets: Boolean = false,
    ): AuditEvent? {
        val current = engine.value ?: return null
        val event = current.event(id) ?: return null
        if (!revealSecrets || current.config.secrets !is Secrets.ShowAfterHandshake || !allows(Scope.ReadSecrets)) return event
        val raw = current.rawPayload(id) ?: return event
        return event.copy(payload = raw)
    }

    public suspend fun body(ref: BodyRef): ByteArray? = engine.value?.body(ref)

    public suspend fun exportHar(includeSecrets: Boolean = false): String = engine.value?.exportHar(includeSecrets) ?: EMPTY_HAR

    public suspend fun exportJsonl(includeSecrets: Boolean = false): String = engine.value?.exportJsonl(includeSecrets).orEmpty()

    public suspend fun clear() {
        engine.value?.clear()
    }

    public suspend fun purge() {
        engine.value?.purgeNow()
    }

    public suspend fun verify(): ChainReport = engine.value?.verify() ?: ChainReport(true, 0, null)

    public suspend fun flush() {
        engine.value?.flush()
    }

    private const val EMPTY_HAR = """{"log":{"version":"1.2","creator":{"name":"StackAudit","version":"0.1.0"},"entries":[]}}"""
}
