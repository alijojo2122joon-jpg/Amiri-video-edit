package com.amiri.cut.ui.common

import androidx.compose.foundation.Canvas
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
    Row(
        modifier
            .pressScale(source)
            .then(if (primary) Modifier.glassAccent(accent, shape) else Modifier.glass(shape))
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                Haptics.select(view); onClick()
            }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) accent else Amiri.TextPrimary, modifier = Modifier.size(18.dp))
            Box(Modifier.size(10.dp))
        }
        Text(text, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
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
        text.uppercase(),
        modifier = modifier.padding(bottom = 8.dp),
        color = Amiri.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
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
    val accent = LocalAccent.current
    val view = LocalView.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .then(if (sel) Modifier.glassAccent(accent, shape) else Modifier.glass(shape, strength = 0.7f))
                    .clickable { Haptics.select(view); onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    label(o),
                    color = if (sel) Amiri.TextPrimary else Amiri.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.glass(RoundedCornerShape(22.dp)).padding(16.dp)) { content() }
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
