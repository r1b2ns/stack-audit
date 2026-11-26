package io.github.r1b2ns.stackaudit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.StackAudit
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class ExportScope(
    val title: String,
) {
    CurrentFilter("Current Filter"),
    Today("Today"),
    EntireLog("Entire Log"),
}

private const val PREVIEW_EVENTS = 2

private const val EXPANDED_LIMIT = 200_000

@Composable
private fun SheetBar(
    title: String,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp)) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { BarText("Cancel", onClick = onCancel) }
        Txt(title, Modifier.align(Alignment.Center).widthIn(max = 220.dp), size = 17f, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SheetForm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .dismissKeyboardOnScroll()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        content = content,
    )
}

@Composable
private fun FormatSection(
    content: @Composable () -> Unit,
    hint: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Format")
        content()
        SectionFooter(hint)
    }
}

@Composable
private fun PreviewSection(
    preview: String,
    lines: Int,
    onCopy: (() -> Unit)?,
    onExpand: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Preview") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                onCopy?.let { HeaderAction("Copy", Icons.Copy, onClick = it) }
                HeaderAction("Expand", enabled = preview.isNotEmpty(), onClick = onExpand)
            }
        }
        CodeBlock(preview.ifEmpty { "—" }, Modifier.tap(preview.isNotEmpty(), onExpand), maxLines = lines)
    }
}

internal fun limitPreview(text: String): String =
    if (text.length <= EXPANDED_LIMIT) {
        text
    } else {
        text.take(EXPANDED_LIMIT) + "\n\n… Preview cut at ${Presentation.bytes(EXPANDED_LIMIT.toLong())}. Share the file to get everything."
    }

@Composable
private fun ExpandedPreview(
    text: String?,
    onBack: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(palette.background)) {
        NavBar(
            title = "Preview",
            large = false,
            leading = { BackButton("Export", onClick = onBack) },
            trailing = { BarIcon(Icons.Copy, enabled = text != null, onClick = onCopy) },
        )
        if (text == null) {
            Txt("Preparing…", Modifier.fillMaxWidth().padding(top = 32.dp), size = 15f, color = palette.secondary, align = TextAlign.Center)
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        ) {
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.codeBackground)
                        .border(1.dp, palette.codeBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Txt(text.ifEmpty { " " }, size = 12f, mono = true, color = palette.codeText, lineSpacing = 4f)
                }
            }
        }
    }
}

private fun isToday(
    epochMs: Long,
    time: Long,
): Boolean {
    val zone = TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date == Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).date
}

@Composable
internal fun BatchExportSheet(route: SheetRoute.Batch) {
    val navigator = LocalNavigator.current
    val copy = LocalCopy.current
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(secretRules(), masks = exportMasks()) }
    val time = remember { now() }
    var selected by remember { mutableStateOf(ExportScope.CurrentFilter) }
    var format by remember { mutableStateOf(BatchFormat.Text) }
    var includeBodies by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<String?>(null) }
    val cache = remember { mutableStateMapOf<String, ExportEvent>() }
    val summaries = { target: ExportScope ->
        when (target) {
            ExportScope.CurrentFilter -> route.filtered
            ExportScope.Today -> route.all.filter { isToday(it.timestamp, time) }
            ExportScope.EntireLog -> route.all
        }
    }
    val bodies = includeBodies && format.supportsBodies
    LaunchedEffect(selected, format, bodies) {
        val head =
            summaries(selected).take(PREVIEW_EVENTS).mapNotNull { summary ->
                cache[summary.id] ?: loadExportEvent(summary.id)?.also { cache[summary.id] = it }
            }
        preview = exporter.batch(head, format, bodies, summaries(selected).size)
    }
    LaunchedEffect(expanded, selected, format, bodies) {
        full = null
        if (!expanded) return@LaunchedEffect
        val events = summaries(selected).mapNotNull { summary -> cache[summary.id] ?: loadExportEvent(summary.id)?.also { cache[summary.id] = it } }
        full = limitPreview(exporter.batch(events, format, bodies))
    }
    val count = summaries(selected).size
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        SheetBar("Export Records") { navigator.dismiss() }
        SheetForm {
            CardSection(title = "Period") {
                ExportScope.entries.forEachIndexed { index, item ->
                    if (index > 0) CardDivider()
                    val total = summaries(item).size
                    val subtitle =
                        when (item) {
                            ExportScope.CurrentFilter -> "${route.filterTitle} · $total events"
                            ExportScope.Today -> "$total events"
                            ExportScope.EntireLog -> "Last ${retentionDays()} days · $total events"
                        }
                    ChoiceRow(item.title, subtitle, selected == item) { selected = item }
                }
            }
            FormatSection({ Segmented(BatchFormat.entries, format, BatchFormat::title, { format = it }) }, format.hint)
            CardSection {
                ToggleRow(
                    "Include Bodies",
                    if (exporter.masks) "Content sent and received, already masked" else "Content sent and received, not masked",
                    checked = bodies,
                    enabled = format.supportsBodies,
                ) { includeBodies = it }
            }
            MaskedSecretsCard(exporter.masks)
            PreviewSection(preview, 18, {
                copy(preview)
                navigator.show(copiedLabel("Copied: Preview", exporter.masks))
            }) { expanded = true }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(if (exporting) "Preparing…" else "Export $count events", icon = Icons.Share, enabled = count > 0 && !exporting) {
                    exporting = true
                    scope.launch {
                        val events = summaries(selected).mapNotNull { cache[it.id] ?: loadExportEvent(it.id) }
                        val text = exporter.batch(events, format, bodies)
                        shareFile("StackAudit-Audit-${exporter.fileStamp(now())}.${format.extension}", format.mimeType, text)
                        StackAudit.recordExport("${format.title} · ${events.size} events · ${selected.title}", events.size.toLong())
                        exporting = false
                    }
                }
                SectionFooter(
                    "The file only leaves the app when you choose where it goes. No copy is kept here, and the export is recorded in the Audit.",
                    align = TextAlign.Center,
                )
            }
        }
    }
    if (expanded) {
        ExpandedPreview(full, onBack = { expanded = false }) {
            full?.let {
                copy(it)
                StackAudit.recordExport("Copied ${format.title} preview · ${selected.title}", count.toLong())
                navigator.show(copiedLabel("Copied", exporter.masks))
            }
        }
    }
    }
}

@Composable
private fun MaskedSecretsCard(masks: Boolean) {
    if (!masks) {
        WarningCard(
            title = "Secrets Are Not Masked",
            text = Exporter.UNMASKED_NOTE,
        )
        return
    }
    WarningCard(
        title = "Secrets Always Leave Masked",
        text = "This can't be turned off:",
    ) {
        Txt(Exporter.maskedItems.joinToString("\n") { "• $it" }, size = 13f, color = palette.warningText, lineSpacing = 3f)
    }
}

@Composable
internal fun EventExportSheet(summary: AuditEventSummary) {
    val navigator = LocalNavigator.current
    val copy = LocalCopy.current
    val exporter = remember { Exporter(secretRules(), masks = exportMasks()) }
    var event by remember(summary.id) { mutableStateOf<ExportEvent?>(null) }
    LaunchedEffect(summary.id) { event = loadExportEvent(summary.id) }
    val isHttp = summary.category == io.github.r1b2ns.stackaudit.Category.Http
    val formats = if (isHttp) EventFormat.entries else listOf(EventFormat.Text, EventFormat.Json)
    var format by remember(summary.id) { mutableStateOf(formats.first()) }
    var includeBody by remember(summary.id) { mutableStateOf(true) }
    var expanded by remember(summary.id) { mutableStateOf(false) }
    val current = event
    val preview = current?.let { exporter.single(it, format, includeBody) }.orEmpty()
    val record = { action: String ->
        StackAudit.recordExport("$action ${format.title} · ${Presentation.method(summary)} ${Presentation.headline(summary)}", 1)
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        SheetBar("Export Event") { navigator.dismiss() }
        SheetForm {
            CardSection {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(Presentation.method(summary), Tone.Blue, mono = true)
                    Spacer(Modifier.width(8.dp))
                    Txt(Presentation.headline(summary), Modifier.weight(1f), size = 15f, weight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Txt(Presentation.shortTime(summary.timestamp), size = 13f, color = palette.secondary)
                }
            }
            FormatSection({ Segmented(formats, format, EventFormat::title, { format = it }) }, format.hint)
            WarningCard(secretNote(current, exporter))
            CardSection {
                val size = (current?.http?.requestBody?.size ?: 0) + (current?.http?.responseBody?.size ?: 0)
                ToggleRow(
                    "Include Bodies",
                    bodiesSubtitle(size, exporter.masks),
                    checked = includeBody,
                ) { includeBody = it }
            }
            PreviewSection(preview, 24, null) { expanded = true }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Copy", Modifier.weight(1f), icon = Icons.Copy, filled = false, enabled = current != null) {
                        copy(preview)
                        record("Copied")
                        navigator.show(copiedLabel("Copied", exporter.masks))
                    }
                    BigButton("Share", Modifier.weight(1f), icon = Icons.Share, enabled = current != null) {
                        shareFile("StackAudit-Event-${exporter.fileStamp(now())}.${format.extension}", format.mimeType, preview)
                        record("Shared")
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                    Glyph(Icons.Doc, 15.dp, palette.secondary, Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(8.dp))
                    Txt("Every export is recorded in the Audit, with its format.", size = 13f, color = palette.secondary)
                }
            }
        }
    }
    if (expanded) {
        ExpandedPreview(limitPreview(preview), onBack = { expanded = false }) {
            copy(preview)
            record("Copied")
            navigator.show(copiedLabel("Copied", exporter.masks))
        }
    }
    }
}

private fun secretNote(
    event: ExportEvent?,
    exporter: Exporter,
): String {
    if (!exporter.masks) return Exporter.UNMASKED_NOTE
    val count = event?.let(exporter::maskedHeaderCount) ?: 0
    if (event?.event?.payload !is HttpPayload || count == 0) {
        return "No secret headers in this event. Secrets in the URL and bodies are masked."
    }
    return "$count secrets will be masked. Secrets in the URL and bodies are masked too."
}

internal fun copiedLabel(
    action: String,
    masks: Boolean,
): String = if (masks) "$action (masked)" else action

internal fun bodiesSubtitle(
    size: Long,
    masks: Boolean,
): String {
    val state = if (masks) "already masked" else "not masked"
    return if (size > 0) "${Presentation.bytes(size)} of request and response, $state" else state.replaceFirstChar { it.uppercase() }
}
