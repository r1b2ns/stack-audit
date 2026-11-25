package io.github.r1b2ns.stackaudit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class Palette(
    val background: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val fill: Color,
    val segmentThumb: Color,
    val gray4: Color,
    val gray5: Color,
    val accent: Color,
    val blueFill: Color,
    val blueText: Color,
    val orangeFill: Color,
    val orangeText: Color,
    val purpleFill: Color,
    val purpleText: Color,
    val greenFill: Color,
    val greenText: Color,
    val redFill: Color,
    val redText: Color,
    val indigo: Color,
    val destructive: Color,
    val systemGreen: Color,
    val systemOrange: Color,
    val warningBackground: Color,
    val warningText: Color,
    val codeBackground: Color,
    val codeText: Color,
    val codeBorder: Color,
    val dim: Color,
    val toast: Color,
    val isDark: Boolean,
) {
    fun tone(tone: Tone): Pair<Color, Color> =
        when (tone) {
            Tone.Blue -> blueFill to blueText
            Tone.Orange -> orangeFill to orangeText
            Tone.Purple -> purpleFill to purpleText
            Tone.Green -> greenFill to greenText
            Tone.Red -> redFill to redText
            Tone.Gray -> gray5 to secondary
        }

    companion object {
        private val accentColor = Color(0xFF0079FF)

        val Light =
            Palette(
                background = Color(0xFFF2F2F7),
                card = Color(0xFFFFFFFF),
                label = Color(0xFF000000),
                secondary = Color(0x993C3C43),
                tertiary = Color(0x4D3C3C43),
                separator = Color(0x4A3C3C43),
                fill = Color(0x1F767680),
                segmentThumb = Color(0xFFFFFFFF),
                gray4 = Color(0xFFD1D1D6),
                gray5 = Color(0xFFE5E5EA),
                accent = accentColor,
                blueFill = Color(0xFFE5F0FF),
                blueText = Color(0xFF004A9E),
                orangeFill = Color(0xFFFFEBD9),
                orangeText = Color(0xFF8A3B00),
                purpleFill = Color(0xFFEFE6FF),
                purpleText = Color(0xFF4B2A9B),
                greenFill = Color(0xFFE6F5EA),
                greenText = Color(0xFF1B6B32),
                redFill = Color(0xFFFFE5E8),
                redText = Color(0xFFB3001A),
                indigo = Color(0xFF3634A3),
                destructive = Color(0xFFD70015),
                systemGreen = Color(0xFF34C759),
                systemOrange = Color(0xFFFF9500),
                warningBackground = Color(0xFFFFF4E5),
                warningText = Color(0xFF5C2700),
                codeBackground = Color(0xFF1C1C1E),
                codeText = Color(0xFFF2F2F7),
                codeBorder = Color(0x14FFFFFF),
                dim = Color(0x66000000),
                toast = Color(0xF7F9F9F9),
                isDark = false,
            )

        val Dark =
            Palette(
                background = Color(0xFF000000),
                card = Color(0xFF1C1C1E),
                label = Color(0xFFFFFFFF),
                secondary = Color(0x99EBEBF5),
                tertiary = Color(0x4DEBEBF5),
                separator = Color(0x99545458),
                fill = Color(0x3D767680),
                segmentThumb = Color(0xFF636366),
                gray4 = Color(0xFF3A3A3C),
                gray5 = Color(0xFF2C2C2E),
                accent = accentColor,
                blueFill = Color(0x380A84FF),
                blueText = Color(0xFF64B5FF),
                orangeFill = Color(0x38FF9F0A),
                orangeText = Color(0xFFFFB35C),
                purpleFill = Color(0x38BF5AF2),
                purpleText = Color(0xFFD7A6FF),
                greenFill = Color(0x3830D158),
                greenText = Color(0xFF6EE08E),
                redFill = Color(0x38FF453A),
                redText = Color(0xFFFF7A70),
                indigo = Color(0xFF3634A3),
                destructive = Color(0xFFFF453A),
                systemGreen = Color(0xFF30D158),
                systemOrange = Color(0xFFFF9F0A),
                warningBackground = Color(0x2EFF9F0A),
                warningText = Color(0xFFFFD6AD),
                codeBackground = Color(0xFF1C1C1E),
                codeText = Color(0xFFF2F2F7),
                codeBorder = Color(0x14FFFFFF),
                dim = Color(0x99000000),
                toast = Color(0xF72C2C2E),
                isDark = true,
            )
    }
}

internal val LocalPalette = staticCompositionLocalOf { Palette.Light }

internal val palette: Palette
    @Composable get() = LocalPalette.current
