package com.amiri.cut.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.glassAccent
import com.amiri.cut.ui.theme.onAccent
import com.amiri.cut.ui.theme.pressScale

/** Quiet backdrop for full screens (Home, Settings): black with one soft brand glow. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color(0xFF050507))
        val c1 = Offset(size.width * 0.12f, size.height * 0.02f)
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.06f), Color.Transparent), center = c1, radius = size.width * 1.05f),
            radius = size.width * 1.05f, center = c1,
        )
        val c2 = Offset(size.width * 1.0f, size.height * 0.62f)
        drawCircle(
            Brush.radialGradient(listOf(Amiri.accent2(accent).copy(alpha = 0.07f), Color.Transparent), center = c2, radius = size.width * 0.9f),
            radius = size.width * 0.9f, center = c2,
        )
    }
}

/** The one primary action of a screen: accent gradient, white label, soft glow. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    val fg = onAccent(accent)
    Row(
        modifier
            .height(height)
            .pressScale(source, 0.97f)
            .drawBehind {
                if (enabled) drawRoundRect(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width / 2f, size.height * 0.9f), radius = size.width * 0.6f),
                    topLeft = Offset(-size.width * 0.1f, size.height * 0.2f), size = Size(size.width * 1.2f, size.height * 1.1f), cornerRadius = CornerRadius(40f),
                )
            }
            .clip(shape)
            .background(if (enabled) Amiri.accentBrush(accent) else SolidColor(Amiri.SurfaceHighest), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { Haptics.confirm(view); onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else Amiri.TextTertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) fg else Amiri.TextTertiary, style = MaterialTheme.typography.labelLarge, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (primary) { PrimaryButton(text, modifier, icon, enabled, onClick = onClick); return }
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(52.dp)
            .pressScale(source, 0.97f)
            .clip(shape)
            .background(Amiri.SurfaceHigh, shape)
            .border(1.dp, Amiri.Line, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { Haptics.select(view); onClick() }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Amiri.TextPrimary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Amiri.TextPrimary, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp)
    }
}

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Amiri.TextPrimary,
    size: Int = 40,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        modifier
            .size(size.dp)
            .pressScale(source, 0.88f)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                Haptics.select(view); onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else Amiri.TextTertiary.copy(alpha = 0.6f), modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Round icon button on a raised disc (top bars). */
@Composable
fun DiscButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, size: Int = 40, tint: Color = Amiri.TextPrimary, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        modifier.size(size.dp).pressScale(source, 0.9f).clip(CircleShape).background(Amiri.SurfaceHigh)
            .border(1.dp, Amiri.Line, CircleShape)
            .clickable(interactionSource = source, indication = null) { Haptics.select(view); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size((size * 0.5f).dp)) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 10.dp),
        color = Amiri.TextPrimary,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** Small caps label above a group of controls. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(top = 12.dp, bottom = 7.dp),
        color = Amiri.TextTertiary, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.8.sp,
    )
}

/** Horizontally scrolling single-choice chips (selected = solid white, CapCut style). */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val shape = RoundedCornerShape(50)
            val bg by animateColorAsState(if (sel) Amiri.TextPrimary else Amiri.SurfaceHigh, label = "chip")
            Box(
                Modifier
                    .height(34.dp)
                    .clip(shape)
                    .background(bg, shape)
                    .then(if (sel) Modifier else Modifier.border(1.dp, Amiri.Line, shape))
                    .clickable { Haptics.select(view); onSelect(o) }
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    color = if (sel) Color(0xFF0B0B0C) else Amiri.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Text tabs with a sliding underline (sheet sections, picker filters). */
@Composable
fun <T> UnderlineTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val view = LocalView.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        options.forEach { o ->
            val on = o == selected
            val w by animateDpAsState(if (on) 18.dp else 0.dp, label = "tab")
            Column(
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Haptics.select(view); onSelect(o) }
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label(o), color = if (on) Amiri.TextPrimary else Amiri.TextTertiary,
                    fontSize = 14.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                )
                Box(Modifier.padding(top = 6.dp).size(width = w, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.TextPrimary))
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(Amiri.SurfaceHigh)
            .border(1.dp, Amiri.Line, RoundedCornerShape(20.dp)).padding(16.dp),
    ) { content() }
}

/**
 * Smooth slider: thin track, accent fill (from the centre for ranges around zero), white knob.
 * Only horizontal drags are claimed, so a slider inside a scrolling panel still lets it scroll.
 */
@Composable
fun AmiriSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val ink = Amiri.accentInk(accent)
    val density = LocalDensity.current
    val knobR = with(density) { 9.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val centered = valueRange.start < 0f && valueRange.endInclusive > 0f
    val zeroFrac = if (centered) ((0f - valueRange.start) / span) else 0f

    fun setAt(x: Float) {
        val usable = (widthPx - 2 * knobR).coerceAtLeast(1f)
        val f = ((x - knobR) / usable).coerceIn(0f, 1f)
        val r = range
        change(r.start + f * (r.endInclusive - r.start))
    }

    Box(
        modifier
            .height(34.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .alpha(if (enabled) 1f else 0.4f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { setAt(it.x); finished?.invoke() }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { d -> dragX += d; setAt(dragX) },
                onDragStarted = { o -> dragging = true; dragX = o.x; setAt(o.x) },
                onDragStopped = { dragging = false; finished?.invoke() },
            )
            .drawBehind {
                val cy = size.height / 2f
                val usable = size.width - 2 * knobR
                val th = 3.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(knobR, cy - th / 2), Size(usable, th), CornerRadius(th))
                val x = knobR + usable * frac
                val x0 = knobR + usable * zeroFrac
                val a = minOf(x, x0); val b = maxOf(x, x0)
                drawRoundRect(ink, Offset(a, cy - th / 2), Size((b - a).coerceAtLeast(0f), th), CornerRadius(th))
                if (centered) drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(x0 - 1.dp.toPx(), cy - 5.dp.toPx()), Size(2.dp.toPx(), 10.dp.toPx()), CornerRadius(1.dp.toPx()))
                val r = if (dragging) knobR * 1.15f else knobR
                drawCircle(Color.Black.copy(alpha = 0.35f), r + 1.5.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
                drawCircle(Color.White, r, Offset(x, cy))
                if (dragging) drawCircle(ink.copy(alpha = 0.25f), r * 2.1f, Offset(x, cy))
            },
    )
}

/** Header of a bottom tool sheet: title in the middle, ✓ on the right. */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onDone: () -> Unit) {
    val view = LocalView.current
    Box(modifier.fillMaxWidth().height(50.dp).padding(horizontal = 10.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(
            title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.align(Alignment.CenterEnd).size(34.dp).pressScale(source, 0.88f).clip(CircleShape).background(Color.White)
                .clickable(interactionSource = source, indication = null) { Haptics.confirm(view); onDone() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Check, "Done", tint = Color(0xFF0B0B0C), modifier = Modifier.size(20.dp)) }
    }
}

/** App-icon style tile: gradient squircle with a white glyph (quick tools, feature entries). */
@Composable
fun AppIconTile(icon: ImageVector, colors: List<Color>, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(colors))
            .drawBehind {
                // soft top highlight
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = this.size.height * 0.55f))
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.48f)) }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val accent = LocalAccent.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Amiri.SurfaceHigh,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amiri.accentInk(accent), unfocusedBorderColor = Amiri.LineStrong,
                    cursorColor = Amiri.accentInk(accent), focusedTextColor = Amiri.TextPrimary, unfocusedTextColor = Amiri.TextPrimary,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirm, color = Amiri.accentInk(accent), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Amiri.TextSecondary) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    danger: Boolean = false,
    dismiss: String = "Cancel",
    onDismiss: () -> Unit,
    onDismissAction: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Amiri.SurfaceHigh,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, color = Amiri.TextSecondary, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) Amiri.Danger else Amiri.accentInk(LocalAccent.current), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { (onDismissAction ?: onDismiss).invoke() }) { Text(dismiss, color = Amiri.TextSecondary) }
        },
    )
}

/** Top bar row with consistent height and padding. */
@Composable
fun BarRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).glassAccent(color, CircleShape))
}

@Composable
fun Gap(h: Int = 0, w: Int = 0) {
    Spacer(Modifier.size(width = w.dp, height = h.dp))
}

/** One row in an action sheet. */
data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Action sheet: a title, optional subtitle, and big tappable rows with icons. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String,
    subtitle: String? = null,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Amiri.Surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.SurfaceTop)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            header?.invoke()
            Text(title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            if (subtitle != null) Text(subtitle, color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            Box(Modifier.height(12.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Amiri.SurfaceHigh)) {
                actions.forEachIndexed { i, a ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = a.enabled) { Haptics.select(view); onDismiss(); a.onClick() }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .alpha(if (a.enabled) 1f else 0.4f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (a.danger) Amiri.Danger.copy(alpha = 0.14f) else Amiri.SurfaceHighest),
                            contentAlignment = Alignment.Center,
                        ) { Icon(a.icon, null, tint = if (a.danger) Amiri.Danger else Amiri.TextPrimary, modifier = Modifier.size(18.dp)) }
                        Text(
                            a.label, color = if (a.danger) Amiri.Danger else Amiri.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    if (i < actions.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(Amiri.Line))
                }
            }
        }
    }
}

/** Small rounded badge (durations, counts). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, bg: Color = Color.Black.copy(alpha = 0.55f), fg: Color = Color.White) {
    Text(
        text, color = fg, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        style = com.amiri.cut.ui.theme.MonoStyle,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Thin offset helper used by a few overlays. */
fun Modifier.nudge(x: Dp = 0.dp, y: Dp = 0.dp): Modifier = this.offset(x, y)
