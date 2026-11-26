package io.github.r1b2ns.stackaudit

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.github.r1b2ns.stackaudit.db.Audit_event
import io.github.r1b2ns.stackaudit.db.StackAuditDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

@OptIn(ExperimentalAtomicApi::class)
internal class AuditEngine(
    val config: StackAuditConfig,
    driver: SqlDriver,
    private val cipher: BlobCipher,
    private val context: CoroutineContext,
    private val now: () -> Long,
    private val purgeInterval: Duration = 1.hours,
) {
    private val database = StackAuditDatabase(driver)
    private val queries = database.auditQueries
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val writeScrubber = SecretScrubber(config.secrets.rules)
    private val exportScrubber = SecretScrubber(config.secrets.rules)
    private val channel = Channel<Pending>(config.queueCapacity)
    private val dropped = AtomicLong(0)
    private var lastHash: String? = queries.lastHash().executeAsOneOrNull()
    private val paused =
        MutableStateFlow(
            queries.setting(PAUSED_CATEGORIES).executeAsOneOrNull()
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.map(Category::fromWire)
                ?.filter { it != Category.Audit }
                ?.toSet()
                .orEmpty(),
        )
    val recording: Flow<Set<Category>> = paused.map { config.categories - it }

    private val limit = MutableStateFlow(queries.setting(STORAGE_LIMIT).executeAsOneOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: config.maxStorage)
    private var writesSinceCheck = 0

    val storageLimit: Flow<Long> = limit

    fun setStorageLimit(bytes: Long) {
        if (bytes <= 0 || bytes == limit.value) return
        limit.value = bytes
        queries.saveSetting(STORAGE_LIMIT, bytes.toString())
        recordMeta(MetaKind.StorageLimitChanged, "Storage limit set to $bytes bytes", bytes)
        offer(Pending.Purge(null))
    }

    fun isRecording(category: Category): Boolean = category in config.categories && category !in paused.value

    fun setRecording(
        category: Category,
        enabled: Boolean,
    ) {
        if (category == Category.Audit || category !in config.categories) return
        val before = paused.value
        val next = if (enabled) before - category else before + category
        if (next == before) return
        paused.value = next
        queries.saveSetting(PAUSED_CATEGORIES, next.joinToString(",") { it.wire })
        recordMeta(MetaKind.RecordingChanged, "Recording of ${category.wire} turned ${if (enabled) "on" else "off"}")
    }
    private val writer: Job = scope.launch { for (item in channel) process(item) }
    private val purger: Job =
        scope.launch {
            while (isActive) {
                channel.send(Pending.Purge(null))
                delay(purgeInterval)
            }
        }

    fun recordHttp(exchange: HttpExchange) {
        if (!isRecording(Category.Http)) return
        offer(Pending.Http(exchange))
    }

    fun recordWebSocket(frame: WebSocketFrame) {
        if (!isRecording(Category.WebSocket)) return
        offer(Pending.WebSocket(frame))
    }

    fun recordStorage(change: StorageChange) {
        if (!isRecording(Category.Storage)) return
        offer(Pending.Storage(change))
    }

    fun recordDatabase(statement: DatabaseStatement) {
        if (!isRecording(Category.Database)) return
        offer(Pending.Database(statement))
    }

    fun recordAnalytics(call: AnalyticsCall) {
        if (!isRecording(Category.Analytics)) return
        offer(Pending.Analytics(call))
    }

    fun recordFlag(
        flag: FlagPayload,
        timestamp: Long,
    ) {
        if (!isRecording(Category.Flag)) return
        offer(Pending.Flag(flag, timestamp))
    }

    val deviceId: String =
        queries.setting(DEVICE_ID).executeAsOneOrNull() ?: Uuid.random().toString().also { queries.saveSetting(DEVICE_ID, it) }

    val nonceStore: NonceStore =
        object : NonceStore {
            override fun seen(nonce: String): Boolean = queries.nonceCount(nonce).executeAsOne() > 0

            override fun remember(
                nonce: String,
                expiresAt: Long,
            ) {
                queries.saveNonce(nonce, expiresAt)
            }
        }

    private val keepRaw: Boolean get() = config.secrets is Secrets.ShowAfterHandshake

    suspend fun rawPayload(id: String): AuditPayload? =
        withContext(context) {
            queries.secretById(id).executeAsOneOrNull()?.let { data ->
                runCatching { AuditJson.decodeFromString(AuditPayload.serializer(), cipher.decrypt(data).decodeToString()) }.getOrNull()
            }
        }

    private fun saveSecret(
        seq: Long,
        raw: AuditPayload,
    ) {
        queries.insertSecret(seq, cipher.encrypt(AuditJson.encodeToString(AuditPayload.serializer(), raw).encodeToByteArray()))
    }

    val overrideStore: OverrideStore =
        object : OverrideStore {
            override fun load(): Map<String, FlagValue> =
                queries.flagOverrides().executeAsList().mapNotNull { row ->
                    runCatching { row.key to AuditJson.decodeFromString(FlagValue.serializer(), row.value_) }.getOrNull()
                }.toMap()

            override fun save(
                key: String,
                value: FlagValue?,
            ) {
                if (value == null) {
                    queries.deleteFlagOverride(key)
                } else {
                    queries.saveFlagOverride(key, AuditJson.encodeToString(FlagValue.serializer(), value))
                }
            }
        }

    fun recordMeta(
        kind: MetaKind,
        message: String,
        count: Long = 0,
    ) {
        offer(Pending.Meta(MetaPayload(kind, message, count), now()))
    }

    private fun offer(item: Pending) {
        if (!channel.trySend(item).isSuccess) dropped.addAndFetch(1)
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        channel.send(Pending.Barrier(done))
        done.await()
    }

    suspend fun purgeNow() {
        val done = CompletableDeferred<Unit>()
        channel.send(Pending.Purge(done))
        done.await()
    }

    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        channel.send(Pending.Clear(done))
        done.await()
    }

    suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        channel.send(Pending.Delete(ids.toSet(), done))
        done.await()
    }

    suspend fun shutdown() {
        purger.cancel()
        recordMeta(MetaKind.Stopped, "StackAudit stopped")
        flush()
        channel.close()
        writer.join()
        scope.cancel()
    }

    fun summaries(
        category: Category?,
        query: String?,
        limit: Long,
    ): Flow<List<AuditEventSummary>> =
        queries
            .summaries(category?.wire, query?.takeIf { it.isNotBlank() }, limit)
            .asFlow()
            .mapToList(context)
            .map { rows ->
                rows.map {
                    AuditEventSummary(
                        id = it.id,
                        timestamp = it.timestamp,
                        durationMs = it.duration,
                        category = Category.fromWire(it.category),
                        source = it.source,
                        title = it.title,
                        method = it.method,
                        host = it.host,
                        status = it.status?.toInt(),
                        requestSize = it.request_size,
                        responseSize = it.response_size,
                    )
                }
            }

    suspend fun event(id: String): AuditEvent? = withContext(context) { queries.eventById(id).executeAsOneOrNull()?.toEvent() }

    suspend fun body(ref: BodyRef): ByteArray? =
        withContext(context) {
            val hash = ref.hash ?: return@withContext null
            queries.blob(hash).executeAsOneOrNull()?.let { open(it, ref.size) }
        }

    private fun open(
        sealed: ByteArray,
        size: Long,
    ): ByteArray? {
        val plain = runCatching { cipher.decrypt(sealed) }.getOrNull() ?: return null
        return if (plain.isEmpty() && size > 0) null else plain
    }

    suspend fun exportHar(includeSecrets: Boolean): String {
        val events = withContext(context) { queries.eventsByCategory(Category.Http.wire).executeAsList().map { it.toEvent() } }
        val har = HarExport.build(events, scrubberFor(includeSecrets)) { body(it) }
        recordMeta(MetaKind.Exported, "HAR export with ${events.size} entries", events.size.toLong())
        return har
    }

    suspend fun exportJsonl(includeSecrets: Boolean): String {
        val scrubber = scrubberFor(includeSecrets)
        val events = withContext(context) { queries.allEvents().executeAsList().map { it.toEvent() } }
        val text =
            events.joinToString("\n") { event ->
                val payload = event.payload
                val safe =
                    when {
                        scrubber == null -> event
                        payload is HttpPayload ->
                            event.copy(
                                payload =
                                    payload.copy(
                                        url = scrubber.url(payload.url),
                                        requestHeaders = scrubber.headers(payload.requestHeaders),
                                        responseHeaders = scrubber.headers(payload.responseHeaders),
                                    ),
                            )
                        payload is WebSocketPayload -> event.copy(payload = payload.copy(url = scrubber.url(payload.url)))
                        else -> event
                    }
                AuditJson.encodeToString(AuditEvent.serializer(), safe)
            }
        recordMeta(MetaKind.Exported, "JSONL export with ${events.size} events", events.size.toLong())
        return text
    }

    suspend fun verify(): ChainReport =
        withContext(context) {
            val rows = queries.allEvents().executeAsList()
            val gaps = queries.gaps().executeAsList().toSet()
            var previous: String? = null
            rows.forEachIndexed { index, row ->
                val expected = chainHash(row.prev_hash, row.id, row.timestamp, row.duration, row.category, row.source, row.trace_id, row.title, row.payload)
                val linked = index == 0 || row.prev_hash == previous || row.prev_hash in gaps
                if (expected != row.hash || !linked) return@withContext ChainReport(false, index, row.id)
                previous = row.hash
            }
            ChainReport(true, rows.size, null)
        }

    private fun scrubberFor(includeSecrets: Boolean): SecretScrubber? = if (includeSecrets || !config.secrets.masksOnWrite) null else exportScrubber

    private fun process(item: Pending) {
        if (item is Pending.Capture && ++writesSinceCheck >= LIMIT_CHECK_EVERY) {
            writesSinceCheck = 0
            safely { if (storageBytes() > limit.value) purge() }
        }
        val lost = dropped.exchange(0)
        if (lost > 0) safely { writeMeta(MetaPayload(MetaKind.Dropped, "$lost events dropped", lost), now()) }
        when (item) {
            is Pending.Http -> safely { writeHttp(item.exchange) }
            is Pending.WebSocket -> safely { writeWebSocket(item.frame) }
            is Pending.Storage -> safely { writeStorage(item.change) }
            is Pending.Analytics -> safely { writeAnalytics(item.call) }
            is Pending.Flag -> safely { writeFlag(item.flag, item.timestamp) }
            is Pending.Database -> safely { writeDatabase(item.statement) }
            is Pending.Meta -> safely { writeMeta(item.payload, item.timestamp) }
            is Pending.Purge -> {
                safely { purge() }
                item.done?.complete(Unit)
            }
            is Pending.Clear -> {
                safely { clearAll() }
                item.done.complete(Unit)
            }
            is Pending.Delete -> {
                safely { deleteEvents(item.ids) }
                item.done.complete(Unit)
            }
            is Pending.Barrier -> item.done.complete(Unit)
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            runCatching {
                writeMeta(MetaPayload(MetaKind.CaptureError, error::class.simpleName + ": " + error.message.orEmpty()), now())
            }
        }
    }

    private fun writeHttp(exchange: HttpExchange) {
        val scrub = config.secrets.masksOnWrite
        val url = if (scrub) writeScrubber.url(exchange.url) else exchange.url
        val request = body(exchange.requestBody, exchange.requestContentType, exchange.requestBodySize, scrub)
        val response = body(exchange.responseBody, exchange.responseContentType, exchange.responseBodySize, scrub)
        val payload =
            HttpPayload(
                method = exchange.method,
                url = url,
                requestHeaders = if (scrub) writeScrubber.headers(exchange.requestHeaders) else exchange.requestHeaders,
                requestBody = request?.ref,
                status = exchange.status,
                responseHeaders = if (scrub) writeScrubber.headers(exchange.responseHeaders) else exchange.responseHeaders,
                responseBody = response?.ref,
                error = exchange.error,
                protocol = exchange.protocol,
            )
        val parts = UrlParts.parse(url)
        val outcome = exchange.status?.toString() ?: "failed"
        val title = "${exchange.method} ${parts.host}${parts.path} $outcome"
        database.transaction {
            val seq =
                insert(
                    timestamp = exchange.startedAt,
                    duration = exchange.durationMs,
                    category = Category.Http,
                    source = exchange.source,
                    traceId = exchange.traceId,
                    title = title,
                    payload = payload,
                )
            listOfNotNull(request, response).forEach { storeBlob(seq, it) }
            if (keepRaw) {
                val rawRequest = body(exchange.requestBody, exchange.requestContentType, exchange.requestBodySize, false)
                val rawResponse = body(exchange.responseBody, exchange.responseContentType, exchange.responseBodySize, false)
                listOfNotNull(rawRequest, rawResponse).forEach { storeBlob(seq, it) }
                saveSecret(
                    seq,
                    payload.copy(
                        url = exchange.url,
                        requestHeaders = exchange.requestHeaders,
                        responseHeaders = exchange.responseHeaders,
                        requestBody = rawRequest?.ref,
                        responseBody = rawResponse?.ref,
                    ),
                )
            }
            queries.insertHttp(
                seq,
                exchange.method,
                parts.host,
                exchange.status?.toLong(),
                request?.ref?.hash?.takeIf { request.data != null },
                response?.ref?.hash?.takeIf { response.data != null },
                request?.ref?.size,
                response?.ref?.size,
            )
        }
    }

    private fun writeWebSocket(frame: WebSocketFrame) {
        val scrub = config.secrets.masksOnWrite
        val url = if (scrub) writeScrubber.url(frame.url) else frame.url
        val stored = body(frame.data, if (frame.isText) null else BINARY, null, scrub)
        val payload =
            WebSocketPayload(
                connectionId = frame.connectionId,
                url = url,
                kind = frame.kind,
                body = stored?.ref,
                code = frame.code,
                reason = frame.reason,
                error = frame.error,
            )
        val parts = UrlParts.parse(url)
        val target = "${parts.host}${parts.path}"
        val size = frame.data?.size ?: 0
        val title =
            when (frame.kind) {
                WebSocketKind.Open -> "WS open $target"
                WebSocketKind.Sent -> "WS ↑ $target ($size B)"
                WebSocketKind.Received -> "WS ↓ $target ($size B)"
                WebSocketKind.Ping -> "WS ping $target"
                WebSocketKind.Pong -> "WS pong $target"
                WebSocketKind.Closing -> "WS closing $target ${frame.code ?: ""}".trimEnd()
                WebSocketKind.Closed -> "WS closed $target ${frame.code ?: ""}".trimEnd()
                WebSocketKind.Failed -> "WS failed $target"
            }
        database.transaction {
            val seq = insert(frame.timestamp, null, Category.WebSocket, frame.source, frame.connectionId, title, payload)
            stored?.let { storeBlob(seq, it) }
            if (keepRaw) {
                val raw = body(frame.data, if (frame.isText) null else BINARY, null, false)
                raw?.let { storeBlob(seq, it) }
                saveSecret(seq, payload.copy(url = frame.url, body = raw?.ref))
            }
        }
    }

    private fun writeStorage(change: StorageChange) {
        val scrub = config.secrets.masksOnWrite
        val payload =
            StoragePayload(
                store = change.store,
                key = change.key,
                operation = change.operation,
                oldValue = limit(if (scrub) writeScrubber.storageValue(change.key, change.oldValue) else change.oldValue),
                newValue = limit(if (scrub) writeScrubber.storageValue(change.key, change.newValue) else change.newValue),
                valueType = change.valueType,
            )
        val verb =
            when (change.operation) {
                StorageOperation.Read -> "read"
                StorageOperation.Write -> "set"
                StorageOperation.Remove -> "remove"
                StorageOperation.Clear -> "clear"
            }
        val title = listOfNotNull(change.store, verb, change.key).joinToString(" ")
        database.transaction {
            val seq = insert(change.timestamp, null, Category.Storage, change.source, null, title, payload)
            if (keepRaw) saveSecret(seq, payload.copy(oldValue = limit(change.oldValue), newValue = limit(change.newValue)))
        }
    }

    private fun writeDatabase(statement: DatabaseStatement) {
        val raw = statement.arguments.map { it?.let(::describe) }
        val scrub = config.secrets.masksOnWrite
        val arguments = if (scrub) writeScrubber.sqlArguments(statement.sql, raw) else raw
        val sql = if (scrub) writeScrubber.sql(statement.sql) else statement.sql
        val payload =
            DatabasePayload(
                database = statement.database,
                sql = sql,
                arguments = arguments.map(::limit),
                rowsAffected = statement.rowsAffected,
                error = statement.error,
            )
        val compact = sql.replace(Regex("\\s+"), " ").trim()
        val title = "${statement.database}: " + if (compact.length > TITLE_SQL) compact.take(TITLE_SQL) + "…" else compact
        database.transaction {
            val seq = insert(statement.timestamp, statement.durationMs, Category.Database, statement.source, null, title, payload)
            if (keepRaw) saveSecret(seq, payload.copy(sql = statement.sql, arguments = raw.map(::limit)))
        }
    }

    private fun writeAnalytics(call: AnalyticsCall) {
        val scrub = config.secrets.masksOnWrite
        val params =
            call.params.mapValues { (key, value) ->
                val text = value?.let(::describe)
                limit(if (scrub) writeScrubber.storageValue(key, text) else text)
            }
        val payload = AnalyticsPayload(call.provider, call.name, params, call.validation, call.issues)
        val marker =
            when (call.validation) {
                ValidationStatus.Invalid -> " (invalid)"
                ValidationStatus.UnknownEvent -> " (not in plan)"
                else -> ""
            }
        database.transaction {
            val seq = insert(call.timestamp, null, Category.Analytics, call.provider, null, "${call.provider}: ${call.name}$marker", payload)
            if (keepRaw) saveSecret(seq, payload.copy(params = call.params.mapValues { (_, value) -> limit(value?.let(::describe)) }))
        }
    }

    private fun writeFlag(
        flag: FlagPayload,
        timestamp: Long,
    ) {
        val title = "flag ${flag.key} = ${flag.effective.display} (${flag.source.name.lowercase()})"
        database.transaction {
            insert(timestamp, null, Category.Flag, flag.provider, null, title, flag)
        }
    }

    private fun describe(value: Any): String =
        when (value) {
            is ByteArray -> "<${value.size} bytes>"
            else -> value.toString()
        }

    private fun limit(value: String?): String? {
        if (value == null) return null
        val max = config.maxBodySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (value.length > max) value.take(max) + "…" else value
    }

    private fun storeBlob(
        seq: Long,
        stored: StoredBody,
    ) {
        val data = stored.data ?: return
        val hash = stored.ref.hash ?: return
        val existing = queries.blob(hash).executeAsOneOrNull()
        when {
            existing == null -> queries.insertBlob(hash, cipher.encrypt(data), data.size.toLong())
            open(existing, data.size.toLong())?.contentEquals(data) != true -> queries.replaceBlob(hash, cipher.encrypt(data), data.size.toLong())
        }
        queries.insertRef(seq, hash)
    }

    private fun writeMeta(
        payload: MetaPayload,
        timestamp: Long,
    ) {
        database.transaction {
            insert(timestamp, null, Category.Audit, "stackaudit", null, payload.message, payload)
        }
    }

    private fun insert(
        timestamp: Long,
        duration: Long?,
        category: Category,
        source: String,
        traceId: String?,
        title: String,
        payload: AuditPayload,
    ): Long {
        val id = Uuid.generateV7().toString()
        val json = AuditJson.encodeToString(AuditPayload.serializer(), payload)
        val hash = chainHash(lastHash, id, timestamp, duration, category.wire, source, traceId, title, json)
        queries.insertEvent(id, timestamp, duration, category.wire, source, traceId, title, json, json.length.toLong(), lastHash, hash)
        lastHash = hash
        return queries.lastSeq().executeAsOne()
    }

    private fun body(
        bytes: ByteArray?,
        contentType: String?,
        declared: Long?,
        scrub: Boolean,
    ): StoredBody? {
        if (bytes == null || bytes.isEmpty()) {
            val size = declared ?: return null
            return if (size > 0) StoredBody(BodyRef(null, size, contentType, stored = false, text = false), null) else null
        }
        val text = BodyKind.isText(contentType, bytes)
        if (bytes.size > config.maxBodySize) {
            return StoredBody(BodyRef(null, maxOf(declared ?: 0, bytes.size.toLong()), contentType, stored = false, text = text), null)
        }
        val data = if (text && scrub) writeScrubber.body(bytes.decodeToString(), contentType).encodeToByteArray() else bytes
        return StoredBody(BodyRef(sha256Hex(data), declared ?: bytes.size.toLong(), contentType, stored = true, text = text), data)
    }

    private fun purge() {
        val before = queries.countEvents().executeAsOne()
        val cutoff = now() - config.retention.inWholeMilliseconds
        queries.lastSeqBefore(cutoff).executeAsOne().MAX?.let(::deleteThrough)
        var rounds = 0
        while (storageBytes() > limit.value && rounds++ < MAX_PURGE_ROUNDS) {
            val count = queries.countEvents().executeAsOne()
            if (count == 0L) break
            deleteThrough(queries.seqAtOffset(maxOf(1, count / 10) - 1).executeAsOne())
        }
        queries.deleteExpiredNonces(now())
        val removed = before - queries.countEvents().executeAsOne()
        if (removed > 0) writeMeta(MetaPayload(MetaKind.Purged, "$removed events purged", removed), now())
    }

    private fun deleteThrough(seq: Long) {
        database.transaction {
            queries.deleteHttpUpTo(seq)
            queries.deleteRefsUpTo(seq)
            queries.deleteSecretsUpTo(seq)
            queries.deleteUpTo(seq)
            queries.deleteOrphanBlobs()
        }
    }

    suspend fun stats(): AuditStats =
        withContext(context) {
            AuditStats(
                events = queries.countEvents().executeAsOne(),
                bytes = storageBytes(),
                oldest = queries.oldestTimestamp().executeAsOne().oldest,
            )
        }

    private fun storageBytes(): Long = queries.eventBytes().executeAsOne() + queries.blobBytes().executeAsOne()

    private fun clearAll() {
        val count = queries.countEvents().executeAsOne()
        database.transaction {
            queries.deleteAllHttp()
            queries.deleteAllRefs()
            queries.deleteAllSecrets()
            queries.deleteAllEvents()
            queries.deleteAllBlobs()
            queries.deleteAllGaps()
        }
        writeMeta(MetaPayload(MetaKind.Cleared, "$count events cleared", count), now())
    }

    private fun deleteEvents(ids: Set<String>) {
        var removed = 0L
        ids.chunked(DELETE_CHUNK).forEach { chunk ->
            database.transaction {
                val rows = queries.eventsByIds(chunk).executeAsList()
                if (rows.isEmpty()) return@transaction
                val seqs = rows.map { it.seq }
                rows.forEach { queries.insertGap(it.hash) }
                queries.deleteHttpBySeq(seqs)
                queries.deleteRefsBySeq(seqs)
                queries.deleteSecretsBySeq(seqs)
                queries.deleteEventsBySeq(seqs)
                removed += rows.size
            }
        }
        if (removed == 0L) return
        queries.deleteOrphanBlobs()
        writeMeta(MetaPayload(MetaKind.Deleted, "$removed events deleted", removed), now())
    }

    private fun Audit_event.toEvent(): AuditEvent =
        AuditEvent(
            id = id,
            timestamp = timestamp,
            durationMs = duration,
            category = Category.fromWire(category),
            source = source,
            traceId = trace_id,
            title = title,
            payload = AuditJson.decodeFromString(AuditPayload.serializer(), payload),
            prevHash = prev_hash,
            hash = hash,
        )

    private class StoredBody(
        val ref: BodyRef,
        val data: ByteArray?,
    )

    private sealed interface Pending {
        sealed interface Capture : Pending

        class Http(val exchange: HttpExchange) : Capture

        class WebSocket(val frame: WebSocketFrame) : Capture

        class Storage(val change: StorageChange) : Capture

        class Analytics(val call: AnalyticsCall) : Capture

        class Flag(val flag: FlagPayload, val timestamp: Long) : Capture

        class Database(val statement: DatabaseStatement) : Capture

        class Meta(val payload: MetaPayload, val timestamp: Long) : Pending

        class Purge(val done: CompletableDeferred<Unit>?) : Pending

        class Clear(val done: CompletableDeferred<Unit>) : Pending

        class Delete(val ids: Set<String>, val done: CompletableDeferred<Unit>) : Pending

        class Barrier(val done: CompletableDeferred<Unit>) : Pending
    }

    private companion object {
        const val MAX_PURGE_ROUNDS = 20
        const val BINARY = "application/octet-stream"
        const val TITLE_SQL = 80
        const val DEVICE_ID = "device_id"
        const val PAUSED_CATEGORIES = "paused_categories"
        const val STORAGE_LIMIT = "storage_limit"
        const val LIMIT_CHECK_EVERY = 50
        const val DELETE_CHUNK = 400
    }
}

internal val AuditJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

internal fun chainHash(
    prevHash: String?,
    id: String,
    timestamp: Long,
    duration: Long?,
    category: String,
    source: String,
    traceId: String?,
    title: String,
    payload: String,
): String =
    sha256Hex(
        listOf(prevHash.orEmpty(), id, timestamp.toString(), duration?.toString().orEmpty(), category, source, traceId.orEmpty(), title, payload)
            .joinToString("") { "${it.length}:$it" },
    )

internal object BodyKind {
    private val textMarkers = listOf("json", "text/", "xml", "x-www-form-urlencoded", "javascript", "graphql", "html")

    fun isText(
        contentType: String?,
        bytes: ByteArray,
    ): Boolean {
        val type = contentType?.lowercase()
        if (type != null) return textMarkers.any { it in type }
        return runCatching { bytes.decodeToString(0, bytes.size, throwOnInvalidSequence = true) }
            .map { decoded -> decoded.none { it.code < 0x09 || (it.code in 0x0e..0x1f) } }
            .getOrDefault(false)
    }
}

internal class UrlParts(
    val scheme: String,
    val host: String,
    val path: String,
    val query: String?,
) {
    companion object {
        fun parse(url: String): UrlParts {
            val schemeEnd = url.indexOf("://")
            val scheme = if (schemeEnd > 0) url.substring(0, schemeEnd) else ""
            val rest = if (schemeEnd > 0) url.substring(schemeEnd + 3) else url
            val pathStart = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, pathStart).substringAfterLast('@')
            val tail = rest.substring(pathStart).substringBefore('#')
            val path = tail.substringBefore('?').ifEmpty { "/" }
            val query = if ('?' in tail) tail.substringAfter('?') else null
            return UrlParts(scheme, authority, path, query)
        }
    }
}
