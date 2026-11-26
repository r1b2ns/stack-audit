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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch

@Composable
private fun FormScreen(
    title: String,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        NavBar(title = title, large = false, leading = { BackButton("Audit") { navigator.pop() } }, trailing = trailing)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

@Composable
internal fun RetentionScreen() {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(AuditStats(0, 0, null)) }
    var reload by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { stats = StackAudit.stats() }
    val config = StackAudit.config
    val days = retentionDays()
    Box(Modifier.fillMaxSize()) {
        FormScreen("Retention") {
            CardSection(
                title = "Keep Records For",
                footer = "Events older than $days days are deleted automatically whenever the app opens. The period is set in the app's StackAudit configuration.",
            ) {
                ChoiceRow("$days days", selected = true) {}
            }
            CardSection(
                title = "What Is Recorded",
                footer = "Recording can't be paused: the audit only counts if it shows everything. Bodies above the limit keep method, address, headers, status and size.",
            ) {
                val categories = config?.categories ?: Category.entries.toSet()
                val rows =
                    listOf(
                        "Full Bodies" to "Up to ${Presentation.bytes(config?.maxBodySize ?: 0)}",
                        "Network Requests" to Category.Http,
                        "WebSocket" to Category.WebSocket,
                        "Storage" to Category.Storage,
                        "Database" to Category.Database,
                        "Analytics" to Category.Analytics,
                        "Feature Flags" to Category.Flag,
                    )
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) CardDivider()
                    val text = if (value is Category) (if (value in categories) "Always" else "Off") else value.toString()
                    LabeledRow(label, text)
                }
            }
            CardSection(title = "Storage") {
                LabeledRow("Events", stats.events.toString())
                CardDivider()
                LabeledRow("Space Used", Presentation.bytes(stats.bytes))
                CardDivider()
                LabeledRow("Limit", Presentation.bytes(config?.maxStorage ?: 0))
                CardDivider()
                LabeledRow("Oldest", stats.oldest?.let(Presentation::shortDate) ?: "—")
            }
            CardSection(title = "Where It Is Stored") {
                LocationRow(Icons.Phone, "Only on this device. StackAudit never sends the log anywhere.")
                LocationRow(Icons.Cloud, "Kept in the app's private storage and never synced between devices.")
                LocationRow(Icons.Lock, "Secrets are masked before they are written, and every event is chained by hash.")
            }
            CardSection(footer = "Events are deleted permanently. New ones keep being recorded.") {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).tap { confirming = true }, contentAlignment = Alignment.Center) {
                    Txt("Delete Entire Log Now", size = 17f, color = palette.destructive)
                }
            }
        }
        ConfirmDialog(
            visible = confirming,
            title = "Delete Entire Log?",
            message = "Events are deleted permanently. New ones keep being recorded.",
            confirm = "Delete",
            onCancel = { confirming = false },
            onConfirm = {
                confirming = false
                scope.launch {
                    StackAudit.clear()
                    reload++
                    navigator.show("Log deleted")
                }
            },
        )
    }
}

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
    FormScreen(
        "Feature Flags",
        trailing = {
            if (canEdit && states.values.any { it.overrideValue != null }) BarText("Reset All") { StackAudit.flags.clearOverrides() }
        },
    ) {
        if (states.isEmpty()) {
            CardSection(footer = "Flags appear here after the app reads them.") {
                Txt("No flag read yet.", Modifier.padding(16.dp), size = 17f, color = palette.secondary)
            }
            return@FormScreen
        }
        CardSection(
            title = "Flags Read by the App",
            footer = if (canEdit) "Overrides apply right away and are recorded in the Audit." else "Overrides need the flags scope.",
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(state.key, Modifier.weight(1f), size = 15f, weight = FontWeight.SemiBold, mono = true)
            Tag(state.source.name, if (state.overrideValue != null) Tone.Orange else Tone.Gray)
        }
        Txt("${state.effective.display} · ${state.provider}", size = 13f, color = palette.secondary)
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
                var text by remember(state.key, state.effective) { mutableStateOf(state.effective.display) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(palette.accent),
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.fill)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    HeaderAction("Set") { Presentation.parseOverride(default, text)?.let { StackAudit.flags.override(state.key, it) } }
                    HeaderAction("Reset", enabled = state.overrideValue != null) { StackAudit.flags.clearOverride(state.key) }
                }
            }
        }
    }
}
