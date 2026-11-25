package io.github.r1b2ns.stackaudit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class Palette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val divider: Color,
    val success: Color,
    val redirect: Color,
    val clientError: Color,
    val serverError: Color,
) {
    fun tone(tone: Tone): Color =
        when (tone) {
            Tone.Success -> success
            Tone.Redirect -> redirect
            Tone.ClientError -> clientError
            Tone.ServerError, Tone.Failed -> serverError
            Tone.Neutral -> muted
        }

    companion object {
        val Light =
            Palette(
                background = Color(0xFFF7F7F8),
                surface = Color(0xFFFFFFFF),
                text = Color(0xFF16181D),
                muted = Color(0xFF6B7080),
                accent = Color(0xFF3B5BDB),
                divider = Color(0xFFE3E5EA),
                success = Color(0xFF2B8A3E),
                redirect = Color(0xFF1971C2),
                clientError = Color(0xFFE67700),
                serverError = Color(0xFFC92A2A),
            )
        val Dark =
            Palette(
                background = Color(0xFF101114),
                surface = Color(0xFF1A1C21),
                text = Color(0xFFE9EAEE),
                muted = Color(0xFF9096A5),
                accent = Color(0xFF7C95F5),
                divider = Color(0xFF2A2D34),
                success = Color(0xFF69DB7C),
                redirect = Color(0xFF74C0FC),
                clientError = Color(0xFFFFC078),
                serverError = Color(0xFFFF8787),
            )
    }
}

internal val LocalPalette = staticCompositionLocalOf { Palette.Light }

internal val palette: Palette
    @Composable get() = LocalPalette.current
