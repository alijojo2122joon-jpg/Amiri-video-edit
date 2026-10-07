package com.amiri.cut.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.glass
import com.amiri.cut.ui.theme.glassAccent
import com.amiri.cut.ui.theme.pressScale

/** Soft ambient light behind glass screens so the translucency reads. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(Amiri.Bg)
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.15f, size.height * 0.12f),
                radius = size.maxDimension * 0.55f,
            ),
            radius = size.maxDimension * 0.55f,
            center = Offset(size.width * 0.15f, size.height * 0.12f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF6E7BFF).copy(alpha = 0.07f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.75f),
                radius = size.maxDimension * 0.5f,
            ),
            radius = size.maxDimension * 0.5f,
            center = Offset(size.width * 0.95f, size.height * 0.75f),
        )
        pawTrailBackground(accent.copy(alpha = 0.05f))
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
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    val fg = if (primary) com.amiri.cut.ui.theme.onAccent(accent) else Amiri.TextPrimary
    Row(
        modifier
            .pressScale(source)
            .clip(shape)
            .background(if (primary) accent else Amiri.SurfaceHigh, shape)
            .then(if (primary) Modifier else Modifier.border(1.dp, Amiri.Line, shape))
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                Haptics.select(view); onClick()
            }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp)
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
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                Haptics.select(view); onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else Amiri.TextTertiary, modifier = Modifier.size((size * 0.52f).dp))
    }
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

/** Horizontally scrolling single-choice chips. */
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .clip(shape)
                    .background(if (sel) Amiri.TextPrimary else Amiri.SurfaceHigh, shape)
                    .clickable { Haptics.select(view); onSelect(o) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    label(o),
                    color = if (sel) Color(0xFF0B0B0C) else Amiri.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Amiri.SurfaceHigh).padding(16.dp)) { content() }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Amiri.SurfaceHigh,
        title = { Text(title, color = Amiri.TextPrimary) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirm) }
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
        title = { Text(title, color = Amiri.TextPrimary) },
        text = { Text(message, color = Amiri.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Amiri.Danger else LocalAccent.current) }
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
    androidx.compose.foundation.layout.Spacer(Modifier.size(width = w.dp, height = h.dp))
}

/** One row in an action sheet. */
data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** CapCut-style action sheet: a title, optional subtitle, and big tappable rows with icons. */
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
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.SurfaceHighest)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            header?.invoke()
            Text(title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
            if (subtitle != null) Text(subtitle, color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            Box(Modifier.height(10.dp))
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Amiri.SurfaceHigh)) {
                actions.forEachIndexed { i, a ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = a.enabled) { Haptics.select(view); onDismiss(); a.onClick() }
                            .padding(horizontal = 16.dp, vertical = 15.dp)
                            .alpha(if (a.enabled) 1f else 0.4f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, tint = if (a.danger) Amiri.Danger else Amiri.TextPrimary, modifier = Modifier.size(21.dp))
                        Text(
                            a.label, color = if (a.danger) Amiri.Danger else Amiri.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    if (i < actions.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 51.dp).height(1.dp).background(Amiri.Line))
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
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.5.dp),
    )
}
