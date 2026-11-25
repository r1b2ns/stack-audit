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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.r1b2ns.stackaudit.AuditEventSummary
import io.github.r1b2ns.stackaudit.BodyRef
import io.github.r1b2ns.stackaudit.HttpPayload
import io.github.r1b2ns.stackaudit.SecretRules
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.WebSocketPayload
import kotlinx.coroutines.delay
import kotlin.time.Clock

internal sealed interface Route {
    data object Events : Route

    data class Detail(
        val summary: AuditEventSummary,
    ) : Route

    data object Retention : Route

    data object Flags : Route
}

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
) {
    val stack = mutableStateListOf<Route>(Route.Events)
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
}

internal val LocalNavigator = staticCompositionLocalOf<AuditNavigator> { error("No navigator") }

internal val LocalCopy = staticCompositionLocalOf<(String) -> Unit> { {} }

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
    val colors = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
    val navigator = remember { AuditNavigator(onClose) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = remember(clipboard) { { clipboard.setText(AnnotatedString(it)) } }
    CompositionLocalProvider(LocalPalette provides colors, LocalNavigator provides navigator, LocalCopy provides copy) {
        Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                        Route.Events -> AuditListScreen()
                        is Route.Detail -> EventDetailScreen(route.summary)
                        Route.Retention -> RetentionScreen()
                        Route.Flags -> FlagsScreen()
                    }
                }
            }
            SheetHost(navigator)
            Toast(navigator.toast)
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
