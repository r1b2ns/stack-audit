package io.github.r1b2ns.stackaudit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.SecretRules
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal sealed interface Route {
    data object Tabs : Route

    data class Detail(
        val summary: AuditEventSummary,
        val back: String,
    ) : Route

    data object Records : Route
}

internal class Confirmation(
    val title: String,
    val message: String,
    val confirm: String,
    val action: suspend () -> Unit,
)

internal sealed interface SheetRoute {
    data class Batch(
        val filtered: List<AuditEventSummary>,
        val all: List<AuditEventSummary>,
        val filterTitle: String,
    ) : SheetRoute

    data class Event(
        val summary: AuditEventSummary,
    ) : SheetRoute
}

@Stable
internal class AuditNavigator(
    val onClose: () -> Unit,
    val fixedTab: Tab? = null,
    private val onSelectTab: (Tab) -> Unit = {},
) {
    val stack = mutableStateListOf<Route>(Route.Tabs)
    var tab by mutableStateOf(fixedTab ?: Tab.Requests)

    fun select(tab: Tab) {
        if (fixedTab != null) onSelectTab(tab) else this.tab = tab
    }
    var confirmation by mutableStateOf<Confirmation?>(null)
    var recordFilter by mutableStateOf(RecordFilter.All)
    var storageFilter by mutableStateOf(StorageFilter.All)
    var storageQuery by mutableStateOf("")
    var sheet by mutableStateOf<SheetRoute?>(null)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.All)
    var segment by mutableStateOf(Segment.Events)

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun present(route: SheetRoute) {
        sheet = route
    }

    fun dismiss() {
        sheet = null
    }

    fun show(message: String) {
        toast = message
        toastId++
    }

    fun ask(confirmation: Confirmation) {
        this.confirmation = confirmation
    }

    fun confirmDelete(
        events: List<AuditEventSummary>,
        onDeleted: () -> Unit = {},
    ) {
        if (events.isEmpty()) return
        val single = events.size == 1
        ask(
            Confirmation(
                title = if (single) "Delete Event?" else "Delete ${events.size} Events?",
                message = "Deleted events can't be recovered. The audit keeps a record of the deletion.",
                confirm = "Delete",
            ) {
                StackAudit.delete(events.map { it.id })
                onDeleted()
                show(if (single) "Event deleted" else "${events.size} events deleted")
            },
        )
    }
}

internal val LocalNavigator = staticCompositionLocalOf<AuditNavigator> { error("No navigator") }

internal val LocalCopy = staticCompositionLocalOf<(String) -> Unit> { {} }

internal val LocalTabBarHeight = staticCompositionLocalOf { 0.dp }

@Composable
internal fun tabContentBottom(): Dp = 40.dp + LocalTabBarHeight.current + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

internal fun now(): Long = Clock.System.now().toEpochMilliseconds()

internal fun secretRules(): SecretRules = StackAudit.config?.secrets?.rules ?: SecretRules.Default

internal fun retentionDays(): Long = StackAudit.config?.retention?.inWholeDays ?: 7

private suspend fun bodyText(ref: BodyRef?): String? {
    if (ref == null || !ref.stored || !ref.text || ref.size == 0L) return null
    return StackAudit.body(ref)?.decodeToString()
}

internal suspend fun loadExportEvent(
    id: String,
    revealSecrets: Boolean = false,
): ExportEvent? {
    val event = StackAudit.event(id, revealSecrets) ?: return null
    val http = event.payload as? HttpPayload
    val socket = event.payload as? WebSocketPayload
    return ExportEvent(
        event = event,
        requestBody = bodyText(http?.requestBody ?: socket?.body),
        responseBody = bodyText(http?.responseBody),
    )
}

@Composable
public fun StackAuditScreen(onClose: () -> Unit) {
    AuditRoot(onClose, fixedTab = null) {}
}

@Composable
internal fun AuditRoot(
    onClose: () -> Unit,
    fixedTab: Tab?,
    onSelectTab: (Tab) -> Unit,
) {
    val colors = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
    val navigator = remember { AuditNavigator(onClose, fixedTab, onSelectTab) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = remember(clipboard) { { clipboard.setText(AnnotatedString(it)) } }
    CompositionLocalProvider(LocalPalette provides colors, LocalNavigator provides navigator, LocalCopy provides copy) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            AnimatedContent(
                targetState = navigator.stack.toList(),
                transitionSpec = {
                    if (targetState.size >= initialState.size) {
                        slideInHorizontally(tween(320)) { it } togetherWith slideOutHorizontally(tween(320)) { -it / 3 }
                    } else {
                        slideInHorizontally(tween(320)) { -it / 3 } togetherWith slideOutHorizontally(tween(320)) { it }
                    }
                },
                contentKey = { it.size to it.last() },
            ) { stack ->
                Box(Modifier.fillMaxSize().background(colors.background)) {
                    when (val route = stack.last()) {
                        Route.Tabs -> TabsScreen(navigator)
                        is Route.Detail -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                            EventDetailScreen(route.summary, route.back)
                        }
                        Route.Records -> RecordsScreen()
                    }
                }
            }
            }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SheetHost(navigator)
            }
            val scope = rememberCoroutineScope()
            val confirmation = navigator.confirmation
            var lastConfirmation by remember { mutableStateOf<Confirmation?>(null) }
            if (confirmation != null) lastConfirmation = confirmation
            val shownConfirmation = lastConfirmation
            ConfirmDialog(
                visible = confirmation != null,
                title = shownConfirmation?.title.orEmpty(),
                message = shownConfirmation?.message.orEmpty(),
                confirm = shownConfirmation?.confirm.orEmpty(),
                onCancel = { navigator.confirmation = null },
                onConfirm = {
                    navigator.confirmation = null
                    shownConfirmation?.let { scope.launch { it.action() } }
                },
            )
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Toast(navigator.toast)
            }
            LaunchedEffect(navigator.toastId) {
                if (navigator.toast != null) {
                    delay(1600)
                    navigator.toast = null
                }
            }
        }
    }
}

@Composable
private fun TabsScreen(navigator: AuditNavigator) {
    val showBar = navigator.fixedTab == null
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTabBarHeight provides if (showBar) TabBarHeight else 0.dp) {
            when (navigator.tab) {
                Tab.Requests -> AuditListScreen()
                Tab.Storage -> StorageScreen()
                Tab.Flags -> FlagsScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
        if (showBar) {
            TabBar(Tab.entries, navigator.tab, Tab::title, ::tabIcon, { navigator.select(it) }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun tabIcon(tab: Tab): ImageVector =
    when (tab) {
        Tab.Requests -> Icons.List
        Tab.Storage -> Icons.Drive
        Tab.Flags -> Icons.Flag
        Tab.Settings -> Icons.Gear
    }

@Composable
private fun SheetHost(navigator: AuditNavigator) {
    val sheet = navigator.sheet
    var shown by remember { mutableStateOf<SheetRoute?>(null) }
    if (sheet != null && sheet != shown) shown = sheet
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(sheet != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.dim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { navigator.dismiss() },
            )
        }
        AnimatedVisibility(
            sheet != null,
            enter = slideInVertically(tween(340)) { it },
            exit = slideOutVertically(tween(280)) { it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(palette.background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                when (val route = shown) {
                    is SheetRoute.Batch -> BatchExportSheet(route)
                    is SheetRoute.Event -> EventExportSheet(route.summary)
                    null -> Unit
                }
            }
        }
    }
}
