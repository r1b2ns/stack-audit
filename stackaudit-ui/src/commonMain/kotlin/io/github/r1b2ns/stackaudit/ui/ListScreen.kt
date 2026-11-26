package io.github.r1b2ns.stackaudit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.StackAudit

@Composable
internal fun AuditListScreen() {
    val navigator = LocalNavigator.current
    val query = navigator.query
    val filter = navigator.filter
    val segment = navigator.segment
    val all by remember { StackAudit.events(limit = 2_000) }.collectAsState(emptyList())
    val time = now()
    val searched = all.filter { Presentation.matchesSearch(it, query) }
    val visible = searched.filter(filter::matches)
    val days = retentionDays()
    val exportBatch = { navigator.present(SheetRoute.Batch(visible, all, filter.title)) }
    Column(Modifier.fillMaxSize()) {
        NavBar(
            title = "Audit",
            large = true,
            leading = { BarText("Close", onClick = navigator.onClose) },
            trailing = {
                BarIcon(Icons.Flag) { navigator.push(Route.Flags) }
                BarIcon(Icons.Share, enabled = all.isNotEmpty(), onClick = exportBatch)
                BarIcon(Icons.Gear) { navigator.push(Route.Retention) }
            },
        )
        SearchBar(query, "Search host or path", { navigator.query = it }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp)) {
            item("banner") { Banner(days) { navigator.push(Route.Retention) } }
            item("tiles") { Tiles(Presentation.today(all, time), Modifier.padding(top = 14.dp)) }
            item("segment") {
                Segmented(Segment.entries, segment, Segment::title, { navigator.segment = it }, Modifier.padding(top = 18.dp))
            }
            when (segment) {
                Segment.Events -> {
                    item("chips") {
                        FlowChips(Filter.entries, filter, Filter::title, { navigator.filter = it }, Modifier.padding(top = 12.dp))
                    }
                    item("count") { CountRow(visible.size, all.isNotEmpty(), exportBatch) }
                    eventSections(visible, time) { navigator.push(Route.Detail(it)) }
                }
                Segment.Destinations -> destinations(Presentation.destinations(searched), days, time) {
                    navigator.show("Copied: $it")
                }
            }
        }
    }
}

@Composable
private fun Banner(
    days: Long,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(palette.indigo), contentAlignment = Alignment.Center) {
            Glyph(Icons.Lock, 16.dp, Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt("Stays on this device", size = 15f, weight = FontWeight.SemiBold)
            Txt("Kept for $days days. Older events are deleted automatically.", size = 13f, color = palette.secondary)
        }
        Glyph(Icons.ChevronRight, 13.dp, palette.tertiary)
    }
}

@Composable
private fun Tiles(
    today: TodayStats,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Requests Today", today.requests.toString(), Modifier.weight(1f))
            Tile("Destinations", today.destinations.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Sent", Presentation.bytes(today.sent), Modifier.weight(1f), Icons.ArrowUp to palette.accent)
            Tile("Received", Presentation.bytes(today.received), Modifier.weight(1f), Icons.ArrowDown to palette.greenText)
        }
    }
}

@Composable
private fun CountRow(
    count: Int,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("$count events in this filter", Modifier.weight(1f), size = 13f, color = palette.secondary)
        Row(
            Modifier.heightIn(min = 44.dp).tap(enabled, onExport).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Icons.Share, 17.dp, palette.accent)
            Spacer(Modifier.width(6.dp))
            Txt("Export", size = 15f, weight = FontWeight.SemiBold, color = palette.accent)
        }
    }
}

private fun shape(
    index: Int,
    count: Int,
): Shape {
    val top = if (index == 0) 10.dp else 0.dp
    val bottom = if (index == count - 1) 10.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private fun LazyListScope.eventSections(
    events: List<AuditEventSummary>,
    time: Long,
    onOpen: (AuditEventSummary) -> Unit,
) {
    if (events.isEmpty()) {
        item("empty") {
            Txt(
                "No events for this filter.",
                Modifier.fillMaxWidth().padding(top = 24.dp),
                size = 15f,
                color = palette.secondary,
                align = TextAlign.Center,
            )
        }
        return
    }
    Presentation.groupByDay(events, time).forEach { group ->
        item("day-${group.title}") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 7.dp)) {
                Txt(group.title.uppercase(), Modifier.weight(1f), size = 13f, color = palette.secondary)
                Txt(Presentation.eventCount(group.events.size).uppercase(), size = 13f, color = palette.secondary)
            }
        }
        itemsIndexed(group.events, key = { _, event -> event.id }) { index, event ->
            Column(Modifier.fillMaxWidth().clip(shape(index, group.events.size)).background(palette.card)) {
                EventRow(event) { onOpen(event) }
                if (index < group.events.size - 1) CardDivider(start = 54.dp)
            }
        }
    }
}

@Composable
private fun EventRow(
    event: AuditEventSummary,
    onClick: () -> Unit,
) {
    val success = Presentation.isSuccess(event)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DirectionBadge(Presentation.isOutbound(event.category), Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(Presentation.method(event), size = 12f, weight = FontWeight.Bold, mono = true, maxLines = 1)
                Txt(Presentation.headline(event), Modifier.weight(1f), size = 15f, weight = FontWeight.SemiBold, maxLines = 1)
                Txt(Presentation.shortTime(event.timestamp), size = 13f, color = palette.secondary, maxLines = 1)
            }
            Txt(Presentation.path(event), size = 13f, mono = true, color = palette.secondary, maxLines = 1)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(Presentation.categoryTitle(event.category), Presentation.categoryTone(event.category))
                Txt(
                    Presentation.statusText(event),
                    size = 12f,
                    weight = FontWeight.SemiBold,
                    color = if (success) palette.greenText else palette.redText,
                    maxLines = 1,
                )
                Txt(Presentation.sizes(event), Modifier.weight(1f), size = 12f, color = palette.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DirectionBadge(
    outbound: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(28.dp).clip(CircleShape).background(if (outbound) palette.blueFill else palette.greenFill),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(if (outbound) Icons.ArrowUpRight else Icons.ArrowDownLeft, 13.dp, if (outbound) palette.accent else palette.greenText)
    }
}

private fun LazyListScope.destinations(
    items: List<Destination>,
    days: Long,
    time: Long,
    onCopy: (String) -> Unit,
) {
    item("destinations-header") {
        Txt(
            "Where the app connects · $days days".uppercase(),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 7.dp),
            size = 13f,
            color = palette.secondary,
        )
    }
    if (items.isEmpty()) {
        item("destinations-empty") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.card).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Txt("No destinations yet.", size = 17f, color = palette.secondary)
            }
        }
    }
    itemsIndexed(items, key = { _, item -> "host-${item.host}" }) { index, destination ->
        val copy = LocalCopy.current
        Column(Modifier.fillMaxWidth().clip(shape(index, items.size)).background(palette.card)) {
            DestinationRow(destination, time) {
                copy(destination.host)
                onCopy(destination.host)
            }
            if (index < items.size - 1) CardDivider(start = 14.dp)
        }
    }
    item("destinations-footer") {
        SectionFooter(
            "Every destination the app contacted shows up here, telemetry and remote configuration included. Nothing is left out.",
            Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    time: Long,
    onCopy: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(destination.host, Modifier.weight(1f), size = 15f, weight = FontWeight.SemiBold, maxLines = 1)
            Txt("${destination.requests} req.", size = 13f, color = palette.secondary)
            Box(Modifier.size(32.dp).tap(onClick = onCopy), contentAlignment = Alignment.Center) {
                Glyph(Icons.Copy, 15.dp, palette.accent)
            }
        }
        Txt(
            "Last request ${Presentation.dayTitle(destination.lastSeen, time).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }} at ${Presentation.shortTime(destination.lastSeen)}",
            size = 13f,
            color = palette.secondary,
        )
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag("Network", Tone.Blue)
            Txt("↑ ${Presentation.bytes(destination.sent)} · ↓ ${Presentation.bytes(destination.received)}", size = 12f, color = palette.secondary)
        }
    }
}
