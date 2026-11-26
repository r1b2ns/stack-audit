package io.github.r1b2ns.stackaudit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 17f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = palette.label,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    lineSpacing: Float = 0f,
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
                textAlign = align,
                lineHeight = if (lineSpacing > 0f) (size * 1.2f + lineSpacing).sp else TextStyle.Default.lineHeight,
            ),
    )
}

@Composable
internal fun Glyph(
    icon: ImageVector,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

@Composable
internal fun Modifier.tap(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .alpha(if (!enabled) 0.4f else if (pressed) 0.55f else 1f)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
internal fun NavBar(
    title: String,
    large: Boolean,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(palette.background)) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
            if (!large) {
                Txt(title, Modifier.align(Alignment.Center).widthIn(max = 220.dp), size = 17f, weight = FontWeight.SemiBold, maxLines = 1)
            }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (large) {
            Txt(title, Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp), size = 34f, weight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun BarText(
    title: String,
    weight: FontWeight = FontWeight.Normal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(Modifier.heightIn(min = 44.dp).tap(enabled, onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Txt(title, size = 17f, weight = weight, color = palette.accent)
    }
}

@Composable
internal fun BarIcon(
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(Modifier.size(44.dp).tap(enabled, onClick), contentAlignment = Alignment.Center) {
        Glyph(icon, 22.dp, palette.accent)
    }
}

@Composable
internal fun BackButton(
    title: String,
    onClick: () -> Unit,
) {
    Row(Modifier.heightIn(min = 44.dp).tap(onClick = onClick).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.ChevronLeft, 22.dp, palette.accent)
        Spacer(Modifier.width(2.dp))
        Txt(title, size = 17f, color = palette.accent)
    }
}

@Composable
internal fun Modifier.dismissKeyboardOnScroll(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val connection =
        remember(focus, keyboard) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        keyboard?.hide()
                        focus.clearFocus()
                    }
                    return Offset.Zero
                }
            }
        }
    return nestedScroll(connection)
}

@Composable
internal fun SearchBar(
    value: String,
    prompt: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.fill)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Search, 17.dp, palette.secondary)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Txt(prompt, size = 17f, color = palette.secondary, maxLines = 1)
            val focus = LocalFocusManager.current
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                textStyle = TextStyle(color = palette.label, fontSize = 17.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).tap { onChange("") }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(17.dp).clip(CircleShape).background(palette.secondary.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Glyph(Icons.Close, 10.dp, palette.card)
                }
            }
        }
    }
}

@Composable
internal fun <T> Segmented(
    items: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.fill)
            .padding(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = item == selected
            val previousSelected = index > 0 && items[index - 1] == selected
            if (index > 0) {
                Box(
                    Modifier
                        .align(Alignment.CenterVertically)
                        .width(1.dp)
                        .height(16.dp)
                        .background(if (isSelected || previousSelected) Color.Transparent else palette.separator),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(7.dp), clip = false)
                                .clip(RoundedCornerShape(7.dp))
                                .background(palette.segmentThumb)
                        } else {
                            Modifier
                        },
                    ).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(item) },
                contentAlignment = Alignment.Center,
            ) {
                Txt(title(item), size = 13f, weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FlowChips(
    items: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Box(
                Modifier
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) palette.accent else palette.card)
                    .then(if (isSelected) Modifier else Modifier.border(1.dp, palette.gray4, CircleShape))
                    .tap { onSelect(item) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(title(item), size = 14f, weight = FontWeight.Medium, color = if (isSelected) Color.White else palette.label, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Tag(
    title: String,
    tone: Tone,
) {
    val (fill, text) = palette.tone(tone)
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(fill).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Txt(title, size = 11f, weight = FontWeight.SemiBold, color = text, maxLines = 1)
    }
}

@Composable
internal fun Pill(
    title: String,
    tone: Tone,
    mono: Boolean = false,
) {
    val (fill, text) = palette.tone(tone)
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Txt(title, size = 13f, weight = FontWeight.Bold, color = text, mono = mono, maxLines = 1)
    }
}

@Composable
internal fun Tile(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    glyph: Pair<ImageVector, Color>? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            glyph?.let {
                Glyph(it.first, 12.dp, it.second)
                Spacer(Modifier.width(4.dp))
            }
            Txt(title, size = 13f, color = palette.secondary, maxLines = 1)
        }
        Txt(value, size = 24f, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title.uppercase(), Modifier.weight(1f), size = 13f, color = palette.secondary)
        trailing()
    }
}

@Composable
internal fun SectionFooter(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    Txt(text, modifier.fillMaxWidth().padding(horizontal = 16.dp), size = 13f, color = palette.secondary, align = align)
}

@Composable
internal fun CardSection(
    title: String? = null,
    footer: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { SectionHeader(it, trailing = trailing) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.card), content = content)
        footer?.let { SectionFooter(it) }
    }
}

@Composable
internal fun CardDivider(start: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(palette.separator))
}

@Composable
internal fun HeaderAction(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.heightIn(min = 28.dp).tap(enabled, onClick), verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Glyph(it, 14.dp, palette.accent)
            Spacer(Modifier.width(4.dp))
        }
        Txt(title, size = 13f, weight = FontWeight.SemiBold, color = palette.accent)
    }
}

@Composable
internal fun CopyIconButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.size(44.dp).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(Icons.Copy, 16.dp, palette.accent)
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = if (onCopy == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(label, size = 15f, color = palette.secondary, maxLines = 1)
        Txt(value, Modifier.weight(1f).padding(vertical = 10.dp), size = 15f, align = TextAlign.End)
        onCopy?.let { CopyIconButton(onClick = it) }
    }
}

@Composable
internal fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.codeBackground)
            .border(1.dp, palette.codeBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Txt(text.ifEmpty { " " }, size = 12f, mono = true, color = palette.codeText, maxLines = maxLines, lineSpacing = 4f)
    }
}

@Composable
internal fun WarningCard(
    text: String,
    title: String? = null,
    icon: ImageVector = Icons.Shield,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.warningBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(icon, 17.dp, palette.systemOrange, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Txt(it, size = 15f, weight = FontWeight.SemiBold, color = palette.warningText) }
            Txt(text, size = if (title == null) 15f else 13f, color = palette.warningText)
            extra()
        }
    }
}

@Composable
internal fun BigButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = if (filled) Color.White else palette.accent
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) palette.accent else palette.card)
            .tap(enabled, onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Glyph(it, 18.dp, content)
            Spacer(Modifier.width(8.dp))
        }
        Txt(title, size = 17f, weight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}

@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, size = 17f)
            subtitle?.let { Txt(it, size = 13f, color = palette.secondary) }
        }
        if (selected) Glyph(Icons.Checkmark, 17.dp, palette.accent)
    }
}

@Composable
internal fun Switch(
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val offset by animateFloatAsState(if (checked) 1f else 0f, tween(180))
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (checked) palette.systemGreen else if (palette.isDark) Color(0xFF39393D) else Color(0xFFE9E9EA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = (2 + 20 * offset).dp, y = 2.dp)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    subtitleSize: Float = 13f,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, size = 17f, color = if (enabled) palette.label else palette.secondary)
            subtitle?.let { Txt(it, size = subtitleSize, color = palette.secondary) }
        }
        Switch(checked, enabled, onChange)
    }
}

@Composable
internal fun LabeledRow(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, Modifier.weight(1f), size = 17f)
        Txt(value, size = 17f, color = palette.secondary, maxLines = 1)
    }
}

@Composable
internal fun BoxScope.Toast(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Box(
            Modifier
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(palette.toast)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Txt(message.orEmpty(), size = 15f, weight = FontWeight.Medium, maxLines = 2)
        }
    }
}

@Composable
internal fun BoxScope.ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.dim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(270.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (palette.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(title, size = 17f, weight = FontWeight.SemiBold, align = TextAlign.Center)
                    Spacer(Modifier.height(2.dp))
                    Txt(message, size = 13f, align = TextAlign.Center)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.separator))
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize().tap(onClick = onCancel), contentAlignment = Alignment.Center) {
                        Txt("Cancel", size = 17f, weight = FontWeight.SemiBold, color = palette.accent)
                    }
                    Box(Modifier.width(0.5.dp).fillMaxSize().background(palette.separator))
                    Box(Modifier.weight(1f).fillMaxSize().tap(onClick = onConfirm), contentAlignment = Alignment.Center) {
                        Txt(confirm, size = 17f, color = palette.destructive)
                    }
                }
            }
        }
    }
}

internal val TabBarHeight = 53.dp

@Composable
internal fun <T> TabBar(
    items: List<T>,
    selected: T,
    title: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(palette.card).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.separator))
        Row(Modifier.fillMaxWidth().height(TabBarHeight - 0.5.dp)) {
            items.forEach { item ->
                val color = if (item == selected) palette.accent else palette.tertiary
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Glyph(icon(item), 24.dp, color)
                    Spacer(Modifier.height(3.dp))
                    Txt(title(item), size = 10f, weight = FontWeight.Medium, color = color, maxLines = 1)
                }
            }
        }
    }
}
