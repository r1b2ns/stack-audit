package io.github.r1b2ns.stackaudit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r1b2ns.stackaudit.AuditStats
import io.github.r1b2ns.stackaudit.Category
import io.github.r1b2ns.stackaudit.FlagState
import io.github.r1b2ns.stackaudit.FlagValue
import io.github.r1b2ns.stackaudit.Scope
import io.github.r1b2ns.stackaudit.StackAudit

@Composable
private fun TabScreen(
    title: String,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        NavBar(title = title, large = true, leading = { BarText("Close", onClick = navigator.onClose) }, trailing = trailing)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = tabContentBottom()),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

@Composable
internal fun SettingsScreen() {
    val navigator = LocalNavigator.current
    var stats by remember { mutableStateOf(AuditStats(0, 0, null)) }
    var reload by remember { mutableIntStateOf(0) }
    val all by remember { StackAudit.events(limit = 2_000) }.collectAsState(emptyList())
    LaunchedEffect(reload, all.size) { stats = StackAudit.stats() }
    val config = StackAudit.config
    val days = retentionDays()
    val records = all.count(RecordFilter.All::matches)
    val recording by remember { StackAudit.recording }.collectAsState(emptySet())
    val limit by remember { StackAudit.storageLimit }.collectAsState(config?.maxStorage ?: 0L)
    TabScreen("Settings") {
            CardSection(
                title = "Keep Records For",
                footer = "Events older than $days days are deleted automatically whenever the app opens. The period is set in the app's StackAudit configuration.",
            ) {
                ChoiceRow("$days days", selected = true) {}
            }
            CardSection(
                title = "What Is Recorded",
                footer = "Your choice is saved on this device and every change is logged in the Audit. Bodies above ${Presentation.bytes(config?.maxBodySize ?: 0)} keep method, address, headers, status and size.",
            ) {
                val configured = config?.categories ?: Category.entries.toSet()
                RecordedCategories.forEachIndexed { index, (label, category) ->
                    if (index > 0) CardDivider()
                    val available = category in configured
                    ToggleRow(
                        title = label,
                        subtitle = if (available) null else "Turned off in the app's configuration",
                        checked = category in recording,
                        enabled = available,
                    ) { StackAudit.setRecording(category, it) }
                }
            }
            CardSection(
                title = "Storage Limit",
                footer = "When the log reaches the limit, the oldest events are deleted automatically. ${if (StorageLimit.of(limit) == null) "Until you choose, the app's limit of ${Presentation.bytes(limit)} applies." else ""}".trim(),
            ) {
                StorageLimit.entries.forEachIndexed { index, option ->
                    if (index > 0) CardDivider()
                    ChoiceRow(option.title, selected = option.bytes == limit) {
                        StackAudit.setStorageLimit(option.bytes)
                        reload++
                    }
                }
            }
            CardSection(title = "Storage") {
                LabeledRow("Events", stats.events.toString())
                CardDivider()
                LabeledRow("Space Used", Presentation.bytes(stats.bytes))
                CardDivider()
                LabeledRow("Limit", Presentation.bytes(limit))
                CardDivider()
                LabeledRow("Oldest", stats.oldest?.let(Presentation::shortDate) ?: "—")
            }
            CardSection(
                title = "Other Records",
                footer = "Feature flag changes and the audit's own log, such as deletions and setting changes.",
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).tap { navigator.push(Route.Records) }.padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("Other Records", Modifier.weight(1f), size = 17f)
                    Txt(records.toString(), size = 17f, color = palette.secondary)
                    Box(Modifier.width(8.dp))
                    Glyph(Icons.ChevronRight, 13.dp, palette.tertiary)
                }
            }
            CardSection(title = "Where It Is Stored") {
                LocationRow(Icons.Phone, "Only on this device. StackAudit never sends the log anywhere.")
                LocationRow(Icons.Cloud, "Kept in the app's private storage and never synced between devices.")
                LocationRow(Icons.Lock, "Secrets are masked before they are written, and every event is chained by hash.")
            }
            CardSection(footer = "Events are deleted permanently. New ones keep being recorded.") {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).tap {
                        navigator.ask(
                            Confirmation(
                                title = "Delete Entire Log?",
                                message = "Events are deleted permanently. New ones keep being recorded.",
                                confirm = "Delete",
                            ) {
                                StackAudit.clear()
                                reload++
                                navigator.show("Log deleted")
                            },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("Delete Entire Log Now", size = 17f, color = palette.destructive)
                }
            }
    }
}

private val RecordedCategories =
    listOf(
        "Network Requests" to Category.Http,
        "WebSocket" to Category.WebSocket,
        "Storage" to Category.Storage,
        "Database" to Category.Database,
        "Analytics" to Category.Analytics,
        "Feature Flags" to Category.Flag,
    )

@Composable
private fun LocationRow(
    icon: ImageVector,
    text: String,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(22.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) { Glyph(icon, 18.dp, palette.secondary) }
        Box(Modifier.width(12.dp))
        Txt(text, Modifier.weight(1f), size = 15f)
    }
}

@Composable
internal fun FlagsScreen() {
    val states by StackAudit.flags.states.collectAsState()
    val canEdit = StackAudit.allows(Scope.Flags)
    TabScreen(
        "Flags",
        trailing = {
            if (canEdit && states.values.any { it.overrideValue != null }) BarText("Reset All") { StackAudit.flags.clearOverrides() }
        },
    ) {
        if (states.isEmpty()) {
            CardSection(footer = "Flags appear here after the app reads them.") {
                Txt("No flag read yet.", Modifier.padding(16.dp), size = 17f, color = palette.secondary)
            }
            return@TabScreen
        }
        CardSection(
            title = "Flags Read by the App",
            footer =
                if (canEdit) {
                    "Overrides apply right away, are recorded in the Audit and stay saved on this device after the app restarts. App goes back to the value the app resolves."
                } else {
                    "Overrides need the flags scope."
                },
        ) {
            states.values.sortedBy { it.key }.forEachIndexed { index, state ->
                if (index > 0) CardDivider()
                FlagRow(state, canEdit)
            }
        }
    }
}

private enum class BoolChoice(
    val title: String,
) {
    App("App"),
    On("On"),
    Off("Off"),
}

@Composable
private fun FlagRow(
    state: FlagState,
    canEdit: Boolean,
) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(state.key, Modifier.weight(1f), size = 15f, weight = FontWeight.SemiBold, mono = true)
            Tag(state.source.name, if (state.overrideValue != null) Tone.Orange else Tone.Gray)
        }
        Txt("${state.effective.display} · ${state.provider}", size = 13f, color = palette.secondary)
        Txt(Presentation.flagOrigin(state), size = 12f, color = palette.tertiary)
        if (!canEdit) return@Column
        when (val default = state.defaultValue) {
            is FlagValue.Bool -> {
                val selected =
                    when ((state.overrideValue as? FlagValue.Bool)?.value) {
                        null -> BoolChoice.App
                        true -> BoolChoice.On
                        false -> BoolChoice.Off
                    }
                Segmented(BoolChoice.entries, selected, BoolChoice::title, { choice ->
                    when (choice) {
                        BoolChoice.App -> StackAudit.flags.clearOverride(state.key)
                        BoolChoice.On -> StackAudit.flags.override(state.key, FlagValue.Bool(true))
                        BoolChoice.Off -> StackAudit.flags.override(state.key, FlagValue.Bool(false))
                    }
                })
            }
            else -> {
                var text by remember(state.key, state.effective) { mutableStateOf(Presentation.editableText(state.effective)) }
                val focus = LocalFocusManager.current
                val apply = {
                    val parsed = Presentation.parseOverride(default, text)
                    if (parsed != null) {
                        StackAudit.flags.override(state.key, parsed)
                        focus.clearFocus()
                    } else {
                        navigator.show("Not a valid ${Presentation.kindTitle(default)}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (default is FlagValue.Number) KeyboardType.Decimal else KeyboardType.Text,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false,
                            ),
                        keyboardActions = KeyboardActions(onDone = { apply() }),
                        textStyle = TextStyle(color = palette.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(palette.accent),
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.fill)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    HeaderAction("Set") { apply() }
                    HeaderAction("Reset", enabled = state.overrideValue != null) {
                        StackAudit.flags.clearOverride(state.key)
                        focus.clearFocus()
                    }
                }
            }
        }
    }
}
