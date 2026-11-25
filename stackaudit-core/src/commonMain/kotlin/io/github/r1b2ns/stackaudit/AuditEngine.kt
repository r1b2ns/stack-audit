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
    private val writer: Job = scope.launch { for (item in channel) process(item) }
    private val purger: Job =
        scope.launch {
            while (isActive) {
                channel.send(Pending.Purge(null))
                delay(purgeInterval)
            }
        }

    fun recordHttp(exchange: HttpExchange) {
        if (Category.Http !in config.categories) return
        offer(Pending.Http(exchange))
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
                    )
                }
            }

    suspend fun event(id: String): AuditEvent? = withContext(context) { queries.eventById(id).executeAsOneOrNull()?.toEvent() }

    suspend fun body(ref: BodyRef): ByteArray? =
        withContext(context) {
            val hash = ref.hash ?: return@withContext null
            queries.blob(hash).executeAsOneOrNull()?.let(cipher::decrypt)
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
                    if (scrubber != null && payload is HttpPayload) {
                        event.copy(
                            payload =
                                payload.copy(
                                    url = scrubber.url(payload.url),
                                    requestHeaders = scrubber.headers(payload.requestHeaders),
                                    responseHeaders = scrubber.headers(payload.responseHeaders),
                                ),
                        )
                    } else {
                        event
                    }
                AuditJson.encodeToString(AuditEvent.serializer(), safe)
            }
        recordMeta(MetaKind.Exported, "JSONL export with ${events.size} events", events.size.toLong())
        return text
    }

    suspend fun verify(): ChainReport =
        withContext(context) {
            val rows = queries.allEvents().executeAsList()
            var previous: String? = null
            rows.forEachIndexed { index, row ->
                val expected = chainHash(row.prev_hash, row.id, row.timestamp, row.duration, row.category, row.source, row.trace_id, row.title, row.payload)
                val linked = index == 0 || row.prev_hash == previous
                if (expected != row.hash || !linked) return@withContext ChainReport(false, index, row.id)
                previous = row.hash
            }
            ChainReport(true, rows.size, null)
        }

    private fun scrubberFor(includeSecrets: Boolean): SecretScrubber? = if (includeSecrets) null else exportScrubber

    private fun process(item: Pending) {
        val lost = dropped.exchange(0)
        if (lost > 0) safely { writeMeta(MetaPayload(MetaKind.Dropped, "$lost events dropped", lost), now()) }
        when (item) {
            is Pending.Http -> safely { writeHttp(item.exchange) }
            is Pending.Meta -> safely { writeMeta(item.payload, item.timestamp) }
            is Pending.Purge -> {
                safely { purge() }
                item.done?.complete(Unit)
            }
            is Pending.Clear -> {
                safely { clearAll() }
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
            listOfNotNull(request, response).forEach { stored ->
                val data = stored.data ?: return@forEach
                queries.insertBlob(stored.ref.hash!!, cipher.encrypt(data), data.size.toLong())
            }
            queries.insertHttp(
                seq,
                exchange.method,
                parts.host,
                exchange.status?.toLong(),
                request?.ref?.hash?.takeIf { request.data != null },
                response?.ref?.hash?.takeIf { response.data != null },
            )
        }
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
        while (storageBytes() > config.maxStorage && rounds++ < MAX_PURGE_ROUNDS) {
            val count = queries.countEvents().executeAsOne()
            if (count == 0L) break
            deleteThrough(queries.seqAtOffset(maxOf(1, count / 10) - 1).executeAsOne())
        }
        val removed = before - queries.countEvents().executeAsOne()
        if (removed > 0) writeMeta(MetaPayload(MetaKind.Purged, "$removed events purged", removed), now())
    }

    private fun deleteThrough(seq: Long) {
        database.transaction {
            queries.deleteHttpUpTo(seq)
            queries.deleteUpTo(seq)
            queries.deleteOrphanBlobs()
        }
    }

    private fun storageBytes(): Long = queries.eventBytes().executeAsOne() + queries.blobBytes().executeAsOne()

    private fun clearAll() {
        val count = queries.countEvents().executeAsOne()
        database.transaction {
            queries.deleteAllHttp()
            queries.deleteAllEvents()
            queries.deleteAllBlobs()
        }
        writeMeta(MetaPayload(MetaKind.Cleared, "$count events cleared", count), now())
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
        class Http(val exchange: HttpExchange) : Pending

        class Meta(val payload: MetaPayload, val timestamp: Long) : Pending

        class Purge(val done: CompletableDeferred<Unit>?) : Pending

        class Clear(val done: CompletableDeferred<Unit>) : Pending

        class Barrier(val done: CompletableDeferred<Unit>) : Pending
    }

    private companion object {
        const val MAX_PURGE_ROUNDS = 20
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
