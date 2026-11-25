package io.github.r1b2ns.stackaudit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.r1b2ns.stackaudit.AnalyticsPayload
import io.github.r1b2ns.stackaudit.AuditEvent
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.DatabasePayload
import io.github.r1b2ns.stackaudit.FlagPayload
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.MetaPayload
import io.github.r1b2ns.stackaudit.Scope
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StoragePayload
import io.github.r1b2ns.stackaudit.WebSocketPayload

private const val COLLAPSED_BODY_LINES = 40

internal enum class DetailTab(
    val title: String,
) {
    Request("Request (Sent)"),
    Response("Response (Received)"),
}

private data class LoadedBody(
    val ref: BodyRef?,
    val content: BodyContent,
)

private data class LoadedDetail(
    val event: AuditEvent,
    val request: LoadedBody,
    val response: LoadedBody,
)

private suspend fun loadBody(ref: BodyRef?): LoadedBody {
    if (ref == null) return LoadedBody(null, BodyContent.Note("No body."))
    val data = if (ref.stored && ref.size > 0) StackAudit.body(ref) else null
    return LoadedBody(ref, Presentation.body(ref, data))
}

@Composable
internal fun EventDetailScreen(summary: AuditEventSummary) {
    val navigator = LocalNavigator.current
    val copy = LocalCopy.current
    var reveal by remember(summary.id) { mutableStateOf(false) }
    var detail by remember(summary.id) { mutableStateOf<LoadedDetail?>(null) }
    var loaded by remember(summary.id) { mutableStateOf(false) }
    val canReveal = StackAudit.config?.secrets is Secrets.ShowAfterHandshake && StackAudit.allows(Scope.ReadSecrets)
    LaunchedEffect(summary.id, reveal) {
        val event = StackAudit.event(summary.id, revealSecrets = reveal)
        detail =
            event?.let {
                val http = it.payload as? HttpPayload
                val socket = it.payload as? WebSocketPayload
                LoadedDetail(it, loadBody(http?.requestBody ?: socket?.body), loadBody(http?.responseBody))
            }
        loaded = true
    }
    val copied = { label: String, value: String ->
        copy(value)
        navigator.show("Copied: $label")
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(
            title = "Event",
            large = false,
            leading = { BackButton("Audit") { navigator.pop() } },
            trailing = { BarIcon(Icons.Share, enabled = detail != null) { navigator.present(SheetRoute.Event(summary)) } },
        )
        val current = detail
        if (current == null) {
            if (loaded) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(Icons.Clock, 44.dp, palette.secondary)
                    Spacer(Modifier.heightIn(min = 12.dp))
                    Txt("Event Not Found", size = 22f, weight = FontWeight.Bold, align = TextAlign.Center)
                    Txt("It may have been deleted by the retention period.", size = 15f, color = palette.secondary, align = TextAlign.Center)
                }
            }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            HeaderCard(current.event, summary, copied)
            InfoSection("Why This Happened", whyRows(current.event), copied)
            InfoSection("Summary", summaryRows(current), copied)
            when (val payload = current.event.payload) {
                is HttpPayload -> HttpSections(payload, current, canReveal, reveal, { reveal = !reveal }, copied)
                else -> PayloadSections(payload, current, copied)
            }
            BigButton("Export This Event", icon = Icons.Share, filled = false) {
                navigator.present(SheetRoute.Event(summary))
            }
        }
    }
}

@Composable
private fun HeaderCard(
    event: AuditEvent,
    summary: AuditEventSummary,
    copied: (String, String) -> Unit,
) {
    val http = event.payload as? HttpPayload
    val (host, path) = http?.let { Presentation.hostAndPath(it.url) } ?: (summary.title to summary.source)
    val success = Presentation.isSuccess(event.category, http?.status, event.title)
    val status = if (http != null) Presentation.statusTitle(http.status, http.error) else Presentation.statusText(summary)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(Presentation.method(summary), Tone.Blue, mono = true)
            Pill(status, if (success) Tone.Green else Tone.Red)
            Spacer(Modifier.weight(1f))
            Tag(Presentation.categoryTitle(event.category), Presentation.categoryTone(event.category))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(host, Modifier.weight(1f), size = 17f, weight = FontWeight.SemiBold)
            CopyIconButton { copied("Host", host) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(path, Modifier.weight(1f), size = 13f, mono = true, color = palette.label.copy(alpha = 0.78f), lineSpacing = 2f)
            CopyIconButton { copied("Path", path) }
        }
        if (http != null) {
            Row(Modifier.heightIn(min = 36.dp).tap { copied("Full URL", http.url) }, verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Link, 17.dp, palette.accent)
                Spacer(Modifier.width(6.dp))
                Txt("Copy Full URL", size = 15f, color = palette.accent)
            }
        }
    }
}

private fun whyRows(event: AuditEvent): List<Pair<String, String>> {
    val notProvided = "Not reported by the caller"
    return listOf(
        "Origin" to event.source.ifEmpty { notProvided },
        "Trace" to (event.traceId ?: notProvided),
        "Integrity" to event.hash.take(12),
    )
}

private fun summaryRows(detail: LoadedDetail): List<Pair<String, String>> {
    val event = detail.event
    val rows = mutableListOf("Date and Time" to Presentation.dateTime(event.timestamp))
    event.durationMs?.let { rows += "Duration" to Presentation.duration(it) }
    when (val payload = event.payload) {
        is HttpPayload -> {
            rows += "Sent" to Presentation.bytes(payload.requestBody?.size ?: 0)
            rows += "Received" to Presentation.bytes(payload.responseBody?.size ?: 0)
            payload.protocol?.let { rows += "Connection" to it }
            payload.error?.let { rows += "Error" to it }
        }
        is WebSocketPayload -> {
            rows += "Event" to payload.kind.name
            rows += "Connection" to payload.connectionId
            payload.body?.let { rows += "Size" to Presentation.bytes(it.size) }
            payload.code?.let { rows += "Code" to it.toString() }
            payload.reason?.takeIf { it.isNotEmpty() }?.let { rows += "Reason" to it }
            payload.error?.let { rows += "Error" to it }
        }
        is StoragePayload -> {
            rows += "Store" to payload.store
            rows += "Operation" to payload.operation.name
            payload.key?.let { rows += "Key" to it }
            payload.valueType?.let { rows += "Type" to it }
        }
        is DatabasePayload -> {
            rows += "Database" to payload.database
            payload.rowsAffected?.let { rows += "Rows Affected" to it.toString() }
            payload.error?.let { rows += "Error" to it }
        }
        is AnalyticsPayload -> {
            rows += "Provider" to payload.provider
            rows += "Event" to payload.name
            rows += "Validation" to payload.validation.name
        }
        is FlagPayload -> {
            rows += "Key" to payload.key
            rows += "Provider" to payload.provider
            rows += "Effective" to payload.effective.display
            rows += "Source" to payload.source.name
            rows += "Default" to payload.defaultValue.display
            rows += "Remote" to (payload.remoteValue?.display ?: "—")
            rows += "Override" to (payload.overrideValue?.display ?: "—")
            payload.changedBy?.let { rows += "Changed By" to it }
        }
        is MetaPayload -> {
            rows += "Kind" to payload.kind.name
            rows += "Message" to payload.message
            if (payload.count > 0) rows += "Count" to payload.count.toString()
        }
    }
    val days = retentionDays()
    rows += "Removed From the Log" to Presentation.shortDate(event.timestamp + days * 86_400_000L)
    return rows
}

@Composable
private fun InfoSection(
    title: String,
    rows: List<Pair<String, String>>,
    copied: (String, String) -> Unit,
) {
    CardSection(title = title) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) CardDivider()
            InfoRow(label, value) { copied(label, value) }
        }
    }
}

@Composable
private fun HttpSections(
    http: HttpPayload,
    detail: LoadedDetail,
    canReveal: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    copied: (String, String) -> Unit,
) {
    var tab by remember(detail.event.id) { mutableStateOf(DetailTab.Response) }
    Segmented(DetailTab.entries, tab, DetailTab::title, { tab = it })
    val headers = if (tab == DetailTab.Request) http.requestHeaders else http.responseHeaders
    val body = if (tab == DetailTab.Request) detail.request else detail.response
    val masked = headers.any { Presentation.isMasked(it.value) }
    HeadersSection(
        headers = headers,
        footer =
            when {
                canReveal -> "Secrets are stored masked and only appear after the handshake. Exports always mask them."
                masked -> "Secrets are masked before they are stored. Copying and exporting always use the masked version."
                else -> null
            },
        trailingReveal = if (canReveal) onToggleReveal else null,
        revealed = revealed,
        onCopyAll = { copied("All Headers", headers.joinToString("\n") { "${it.name}: ${it.value}" }) },
        onCopy = { copied(it.name, it.value) },
    )
    BodySection(body) { text -> copied("Body", text) }
}

@Composable
private fun HeadersSection(
    headers: List<Header>,
    footer: String?,
    trailingReveal: (() -> Unit)?,
    revealed: Boolean,
    onCopyAll: () -> Unit,
    onCopy: (Header) -> Unit,
) {
    CardSection(
        title = "Headers",
        footer = footer,
        trailing = {
            trailingReveal?.let {
                HeaderAction(if (revealed) "Hide Secrets" else "Reveal Secrets", onClick = it)
                Spacer(Modifier.width(16.dp))
            }
            HeaderAction("Copy All", enabled = headers.isNotEmpty(), onClick = onCopyAll)
        },
    ) {
        if (headers.isEmpty()) {
            Txt("No headers.", Modifier.padding(16.dp), size = 17f, color = palette.secondary)
        }
        headers.forEachIndexed { index, header ->
            if (index > 0) CardDivider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(header.name, size = 12f, mono = true, color = palette.secondary)
                        if (Presentation.isMasked(header.value)) Tag("SECRET", Tone.Orange)
                    }
                    Txt(header.value, size = 13f, mono = true, lineSpacing = 3f)
                }
                CopyIconButton { onCopy(header) }
            }
        }
    }
}

@Composable
private fun BodySection(
    body: LoadedBody,
    onCopy: (String) -> Unit,
) {
    var expanded by remember(body) { mutableStateOf(false) }
    val content = body.content
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val meta = body.ref?.let(Presentation::bodyMeta) ?: Presentation.bytes(0)
        SectionHeader("Body · $meta") {
            if (content is BodyContent.Text) HeaderAction("Copy", Icons.Copy) { onCopy(content.text) }
        }
        when (content) {
            is BodyContent.Text -> {
                val long = Presentation.lineCount(content.text) > COLLAPSED_BODY_LINES
                CodeBlock(content.text, maxLines = if (expanded || !long) Int.MAX_VALUE else COLLAPSED_BODY_LINES)
                if (long) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).tap { expanded = !expanded }, contentAlignment = Alignment.Center) {
                        Txt(if (expanded) "Show Less" else "View Full Body", size = 15f, color = palette.accent)
                    }
                }
            }
            is BodyContent.Note -> BodyNote(content.text)
        }
    }
}

@Composable
private fun BodyNote(text: String) {
    Txt(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.card).padding(16.dp),
        size = 15f,
        color = palette.secondary,
    )
}

@Composable
private fun CodeSection(
    title: String,
    text: String,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title) { HeaderAction("Copy", Icons.Copy) { onCopy(text) } }
        CodeBlock(text)
    }
}

@Composable
private fun ColumnScope.PayloadSections(
    payload: Any,
    detail: LoadedDetail,
    copied: (String, String) -> Unit,
) {
    when (payload) {
        is WebSocketPayload -> {
            InfoSection("Connection", listOf("URL" to payload.url), copied)
            if (payload.body != null) BodySection(detail.request) { copied("Message", it) }
        }
        is StoragePayload -> {
            payload.oldValue?.let { CodeSection("Old Value", it) { text -> copied("Old Value", text) } }
            payload.newValue?.let { CodeSection("New Value", it) { text -> copied("New Value", text) } }
        }
        is DatabasePayload -> {
            CodeSection("SQL", payload.sql) { copied("SQL", it) }
            if (payload.arguments.isNotEmpty()) {
                InfoSection("Arguments", payload.arguments.mapIndexed { index, value -> "#${index + 1}" to (value ?: "NULL") }, copied)
            }
        }
        is AnalyticsPayload -> {
            if (payload.issues.isNotEmpty()) {
                WarningCard(payload.issues.joinToString("\n") { "• $it" }, title = "Issues", icon = Icons.Warning)
            }
            CardSection(title = "Parameters") {
                if (payload.params.isEmpty()) Txt("No parameters.", Modifier.padding(16.dp), size = 17f, color = palette.secondary)
                payload.params.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) CardDivider()
                    InfoRow(key, value ?: "null") { copied(key, value ?: "null") }
                }
            }
        }
        else -> Unit
    }
}
