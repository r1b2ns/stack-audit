package io.github.r1b2ns.stackaudit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r1b2ns.stackaudit.AuditEvent
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.Curl
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.MetaPayload
import io.github.r1b2ns.stackaudit.Scope
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.AnalyticsPayload
import io.github.r1b2ns.stackaudit.DatabasePayload
import io.github.r1b2ns.stackaudit.FlagPayload
import io.github.r1b2ns.stackaudit.FlagState
import io.github.r1b2ns.stackaudit.FlagValue
import io.github.r1b2ns.stackaudit.StoragePayload
import io.github.r1b2ns.stackaudit.WebSocketPayload
import kotlinx.coroutines.launch

@Composable
public fun StackAuditScreen(onClose: () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
    CompositionLocalProvider(LocalPalette provides colors) {
        var selected by remember { mutableStateOf<String?>(null) }
        var showFlags by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val id = selected
            when {
                showFlags -> FlagsScreen(onBack = { showFlags = false })
                id == null -> EventList(onOpen = { selected = it }, onFlags = { showFlags = true }, onClose = onClose)
                else -> EventDetail(id = id, onBack = { selected = null })
            }
        }
    }
}

@Composable
private fun EventList(
    onOpen: (String) -> Unit,
    onFlags: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Category?>(null) }
    val events by remember(query, category) { StackAudit.events(category, query) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "StackAudit") {
            Action("Flags", onFlags)
            Action("Export") { scope.launch { shareFile("stackaudit.har", "application/json", StackAudit.exportHar()) } }
            Action("Clear") { scope.launch { StackAudit.clear() } }
            Action("Close", onClose)
        }
        SearchField(query) { query = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Presentation.filters.forEach { filter ->
                Chip(Presentation.filterLabel(filter), selected = filter == category) { category = filter }
            }
        }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("Nothing recorded yet", color = palette.muted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { EventRow(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: AuditEventSummary,
    onClick: () -> Unit,
) {
    val tone = palette.tone(Presentation.tone(event.category, event.status, event.title))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).background(palette.surface)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(tone))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(event.title, weight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Label(
                    listOf(Presentation.time(event.timestamp), Presentation.duration(event.durationMs), event.source)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    color = palette.muted,
                    size = 12,
                )
            }
            event.status?.let { Label(it.toString(), color = tone, weight = FontWeight.Bold) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}

@Composable
private fun EventDetail(
    id: String,
    onBack: () -> Unit,
) {
    var event by remember(id) { mutableStateOf<AuditEvent?>(null) }
    var requestBody by remember(id) { mutableStateOf<String?>(null) }
    var responseBody by remember(id) { mutableStateOf<String?>(null) }
    var reveal by remember(id) { mutableStateOf(false) }
    val canReveal = StackAudit.config?.secrets is Secrets.ShowAfterHandshake && StackAudit.allows(Scope.ReadSecrets)
    LaunchedEffect(id, reveal) {
        val loaded = StackAudit.event(id, revealSecrets = reveal)
        event = loaded
        val http = loaded?.payload as? HttpPayload
        val socket = loaded?.payload as? WebSocketPayload
        requestBody = (http?.requestBody ?: socket?.body)?.let { Presentation.body(it, StackAudit.body(it)) }
        responseBody = http?.responseBody?.let { Presentation.body(it, StackAudit.body(it)) }
    }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Details", leading = { Action("Back", onBack) }) {
            if (canReveal) Action(if (reveal) "Mask" else "Reveal") { reveal = !reveal }
            val http = event?.payload as? HttpPayload
            if (http != null) {
                Action("cURL") { clipboard.setText(AnnotatedString(Curl.from(http, requestBody))) }
            }
        }
        val current = event ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (val payload = current.payload) {
                is HttpPayload -> HttpDetail(current, payload, requestBody, responseBody)
                is WebSocketPayload -> {
                    Section("WebSocket")
                    KeyValue("URL", payload.url, mono = true)
                    KeyValue("Event", payload.kind.name)
                    KeyValue("Connection", payload.connectionId, mono = true)
                    KeyValue("Time", Presentation.time(current.timestamp))
                    KeyValue("Source", current.source)
                    payload.code?.let { KeyValue("Code", it.toString()) }
                    payload.reason?.takeIf { it.isNotEmpty() }?.let { KeyValue("Reason", it) }
                    payload.error?.let { KeyValue("Error", it) }
                    requestBody?.let {
                        Section("Message")
                        Code(it)
                    }
                }
                is StoragePayload -> {
                    Section("Storage")
                    KeyValue("Store", payload.store)
                    KeyValue("Operation", payload.operation.name)
                    payload.key?.let { KeyValue("Key", it, mono = true) }
                    payload.valueType?.let { KeyValue("Type", it) }
                    KeyValue("Time", Presentation.time(current.timestamp))
                    KeyValue("Source", current.source)
                    payload.oldValue?.let {
                        Section("Old value")
                        Code(it)
                    }
                    payload.newValue?.let {
                        Section("New value")
                        Code(it)
                    }
                }
                is DatabasePayload -> {
                    Section("Database")
                    KeyValue("Database", payload.database)
                    KeyValue("Duration", Presentation.duration(current.durationMs))
                    KeyValue("Time", Presentation.time(current.timestamp))
                    KeyValue("Source", current.source)
                    payload.rowsAffected?.let { KeyValue("Rows affected", it.toString()) }
                    payload.error?.let { KeyValue("Error", it) }
                    Section("SQL")
                    Code(payload.sql)
                    if (payload.arguments.isNotEmpty()) {
                        Section("Arguments")
                        payload.arguments.forEachIndexed { index, value -> KeyValue("#${index + 1}", value ?: "NULL", mono = true) }
                    }
                }
                is AnalyticsPayload -> {
                    Section("Analytics")
                    KeyValue("Provider", payload.provider)
                    KeyValue("Event", payload.name, mono = true)
                    KeyValue("Validation", payload.validation.name)
                    KeyValue("Time", Presentation.time(current.timestamp))
                    if (payload.issues.isNotEmpty()) {
                        Section("Issues")
                        payload.issues.forEach { Label("• $it", color = palette.clientError) }
                    }
                    Section("Parameters")
                    if (payload.params.isEmpty()) Label("None", color = palette.muted)
                    payload.params.forEach { (key, value) -> KeyValue(key, value ?: "null", mono = true) }
                }
                is FlagPayload -> {
                    Section("Feature flag")
                    KeyValue("Key", payload.key, mono = true)
                    KeyValue("Provider", payload.provider)
                    KeyValue("Effective", payload.effective.display)
                    KeyValue("Source", payload.source.name)
                    KeyValue("Default", payload.defaultValue.display)
                    KeyValue("Remote", payload.remoteValue?.display ?: "-")
                    KeyValue("Override", payload.overrideValue?.display ?: "-")
                    payload.changedBy?.let { KeyValue("Changed by", it) }
                    KeyValue("Time", Presentation.time(current.timestamp))
                }
                is MetaPayload -> {
                    Section("Event")
                    KeyValue("Kind", payload.kind.name)
                    KeyValue("Message", payload.message)
                    KeyValue("Time", Presentation.time(current.timestamp))
                }
            }
            Section("Integrity")
            KeyValue("Hash", current.hash, mono = true)
            KeyValue("Previous", current.prevHash ?: "-", mono = true)
        }
    }
}

@Composable
private fun FlagsScreen(onBack: () -> Unit) {
    val states by StackAudit.flags.states.collectAsState()
    val canEdit = StackAudit.allows(Scope.Flags)
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Feature flags", leading = { Action("Back", onBack) }) {
            if (canEdit) Action("Reset all") { StackAudit.flags.clearOverrides() }
        }
        if (states.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("No flag read yet", color = palette.muted)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(states.values.sortedBy { it.key }, key = { it.key }) { FlagRow(it, canEdit) }
        }
    }
}

@Composable
private fun FlagRow(
    state: FlagState,
    canEdit: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Label(state.key, weight = FontWeight.Medium, mono = true)
        Label("${state.effective.display} · ${state.source.name.lowercase()} · ${state.provider}", color = palette.muted, size = 12)
        if (!canEdit) {
            Spacer(Modifier.height(4.dp))
            Label("Overrides need the flags scope", color = palette.muted, size = 12)
        }
        Spacer(Modifier.height(8.dp))
        if (canEdit) when (val current = state.defaultValue) {
            is FlagValue.Bool ->
                Row {
                    val effective = (state.effective as? FlagValue.Bool)?.value
                    Chip("On", selected = state.overrideValue != null && effective == true) { StackAudit.flags.override(state.key, FlagValue.Bool(true)) }
                    Chip("Off", selected = state.overrideValue != null && effective == false) { StackAudit.flags.override(state.key, FlagValue.Bool(false)) }
                    Chip("Reset", selected = state.overrideValue == null) { StackAudit.flags.clearOverride(state.key) }
                }
            else -> {
                var text by remember(state.key, state.effective) { mutableStateOf(state.effective.display) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SearchField(text) { text = it } }
                    Action("Set") { Presentation.parseOverride(current, text)?.let { StackAudit.flags.override(state.key, it) } }
                    Action("Reset") { StackAudit.flags.clearOverride(state.key) }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
}

@Composable
private fun HttpDetail(
    event: AuditEvent,
    http: HttpPayload,
    requestBody: String?,
    responseBody: String?,
) {
    Section("Overview")
    KeyValue("URL", http.url, mono = true)
    KeyValue("Method", http.method)
    KeyValue("Status", http.status?.toString() ?: (http.error ?: "Failed"))
    KeyValue("Duration", Presentation.duration(event.durationMs))
    KeyValue("Time", Presentation.time(event.timestamp))
    KeyValue("Source", event.source)
    http.protocol?.let { KeyValue("Protocol", it) }
    http.error?.let { KeyValue("Error", it) }
    Section("Request headers")
    Headers(http.requestHeaders)
    requestBody?.let {
        Section("Request body")
        Code(it)
    }
    Section("Response headers")
    Headers(http.responseHeaders)
    responseBody?.let {
        Section("Response body")
        Code(it)
    }
}

@Composable
private fun Headers(headers: List<Header>) {
    if (headers.isEmpty()) Label("None", color = palette.muted)
    headers.forEach { KeyValue(it.name, it.value, mono = true) }
}

@Composable
private fun TopBar(
    title: String,
    leading: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Label(title, weight = FontWeight.Bold, size = 18, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
private fun Action(
    label: String,
    onClick: () -> Unit,
) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Label(label, color = palette.accent, weight = FontWeight.Medium)
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Label("Search", color = palette.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.text, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) palette.accent else palette.surface
    val text = if (selected) Color.White else palette.text
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Label(label, color = text, size = 13)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Label(title.uppercase(), color = palette.muted, weight = FontWeight.Bold, size = 12)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun KeyValue(
    key: String,
    value: String,
    mono: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Label(key, color = palette.muted, size = 12)
        Label(value, mono = mono, size = 14)
    }
}

@Composable
private fun Code(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Label(text, mono = true, size = 13)
    }
}

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = palette.text,
    weight: FontWeight = FontWeight.Normal,
    size: Int = 15,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style =
            TextStyle(
                color = color,
                fontSize = size.sp,
                fontWeight = weight,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
    )
}
