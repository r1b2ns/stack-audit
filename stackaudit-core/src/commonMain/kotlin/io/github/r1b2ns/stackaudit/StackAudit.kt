package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

public object StackAudit : HttpRecorder {
    private val engine = MutableStateFlow<AuditEngine?>(null)
    private var viewer: AuditViewer? = null

    public val isStarted: Boolean get() = engine.value != null

    public val config: StackAuditConfig? get() = engine.value?.config

    override val isCapturing: Boolean get() = engine.value?.config?.categories?.contains(Category.Http) == true

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
        val created = AuditEngine(config, driver, cipher, context, now)
        if (!engine.compareAndSet(null, created)) return
        if (config.secrets is Secrets.Show && !isDebuggableBuild()) {
            platformLog("StackAudit: Secrets.Show is active in a release build. Secrets are stored in clear text.")
        }
        created.recordMeta(MetaKind.Started, "StackAudit started")
    }

    public suspend fun stop() {
        val current = engine.value ?: return
        if (engine.compareAndSet(current, null)) current.shutdown()
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
            is Access.Handshake -> {
                platformLog("StackAudit: access requires a signed handshake token.")
                false
            }
            Access.Open -> {
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

    override fun record(exchange: HttpExchange) {
        engine.value?.recordHttp(exchange)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    public fun events(
        category: Category? = null,
        query: String? = null,
        limit: Long = 500,
    ): Flow<List<AuditEventSummary>> = engine.flatMapLatest { it?.summaries(category, query, limit) ?: flowOf(emptyList()) }

    public suspend fun event(id: String): AuditEvent? = engine.value?.event(id)

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
