package com.amiri.cut.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.anim.ClipAnimSpec
import com.amiri.cut.core.anim.ClipAnims
import com.amiri.cut.core.model.ClipAnim
import com.amiri.cut.ui.common.AmiriSlider
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.GroupLabel
import com.amiri.cut.ui.common.UnderlineTabs
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent

// ═══════════════════════════════ Animation (In / Out / Combo) ═══════════════════════════════

@Composable
internal fun AnimationPanel(c: EditorController) {
    val clip = c.selectedClip()
    if (clip == null) {
        Text(
            "Select a clip, photo, sticker or text on the timeline to animate it.",
            color = Amiri.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
        return
    }
    val view = LocalView.current
    var tab by remember { mutableStateOf("In") }
    val a = clip.anim ?: ClipAnim()
    Column(Modifier.padding(horizontal = 6.dp)) {
        UnderlineTabs(listOf("In", "Out", "Combo"), tab, { it }) { tab = it }
        val list: List<ClipAnimSpec> = if (tab == "Combo") ClipAnims.COMBO else ClipAnims.IN
        val current = when (tab) { "In" -> a.inId; "Out" -> a.outId; else -> a.comboId }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimTile(null, "None", tab, current == ClipAnims.NONE) {
                Haptics.select(view)
                c.setAnim("No ${tab.lowercase()} animation") { when (tab) { "In" -> it.copy(inId = ClipAnims.NONE); "Out" -> it.copy(outId = ClipAnims.NONE); else -> it.copy(comboId = ClipAnims.NONE) } }
            }
            list.forEach { spec ->
                AnimTile(spec, spec.label, tab, current == spec.id) {
                    Haptics.select(view)
                    c.setAnim("${tab} animation: ${spec.label}") {
                        when (tab) {
                            // A combo and an entrance/exit can be combined; picking a combo keeps them.
                            "In" -> it.copy(inId = spec.id)
                            "Out" -> it.copy(outId = spec.id)
                            else -> it.copy(comboId = spec.id)
                        }
                    }
                    c.previewAnim(tab.lowercase())
                }
            }
        }
        val durUs = clip.durationUs
        when (tab) {
            "In" -> if (a.inId != ClipAnims.NONE) DurationRow("Duration", a.inDur, (durUs / 1e6f * 0.9f).coerceIn(0.2f, 5f),
                onChange = { v -> c.setAnim("In duration", live = true) { it.copy(inDur = v) } }, onEnd = { c.endEdit("In duration"); c.previewAnim("in") })
            "Out" -> if (a.outId != ClipAnims.NONE) DurationRow("Duration", a.outDur, (durUs / 1e6f * 0.9f).coerceIn(0.2f, 5f),
                onChange = { v -> c.setAnim("Out duration", live = true) { it.copy(outDur = v) } }, onEnd = { c.endEdit("Out duration"); c.previewAnim("out") })
            else -> if (a.comboId != ClipAnims.NONE) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Speed", color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                AmiriSlider(a.comboSpeed, { v -> c.setAnim("Combo speed", live = true) { it.copy(comboSpeed = v) } }, Modifier.weight(1f), 0.25f..3f, onValueChangeFinished = { c.endEdit("Combo speed") })
                Text(String.format(java.util.Locale.US, "%.2f×", a.comboSpeed), color = Amiri.TextPrimary, style = com.amiri.cut.ui.theme.MonoStyle, fontSize = 12.sp, modifier = Modifier.width(52.dp).padding(start = 8.dp))
            }
        }
        Text(
            when (tab) {
                "In" -> "Plays when the clip starts. Works on videos, photos, stickers and text."
                "Out" -> "Plays as the clip ends."
                else -> "Runs through the whole clip — can be mixed with an In and an Out."
            },
            color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

@Composable
private fun DurationRow(label: String, value: Float, max: Float, onChange: (Float) -> Unit, onEnd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(label, color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        AmiriSlider(value.coerceIn(0.1f, max), onChange, Modifier.weight(1f), 0.1f..max, onValueChangeFinished = onEnd)
        Text(String.format(java.util.Locale.US, "%.1fs", value), color = Amiri.TextPrimary, style = com.amiri.cut.ui.theme.MonoStyle, fontSize = 12.sp, modifier = Modifier.width(52.dp).padding(start = 8.dp))
    }
}

/** A preset tile with a small looping preview of the motion on a mini picture. */
@Composable
private fun AnimTile(spec: ClipAnimSpec?, label: String, tab: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // With system animations switched off the loop can't run: show the resting frame instead.
    val motionOff = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val inf = rememberInfiniteTransition(label = "anim")
    val tLoop by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    val t = if (motionOff) (if (tab == "Out") 0.2f else 0.75f) else tLoop
    val xf = remember { com.amiri.cut.core.anim.ClipXf() }
    if (spec != null) {
        val durUs = 1_800_000L
        val anim = when (tab) {
            "In" -> ClipAnim(inId = spec.id, inDur = 0.9f)
            "Out" -> ClipAnim(outId = spec.id, outDur = 0.9f)
            else -> ClipAnim(comboId = spec.id)
        }
        // In: animate during the first half, hold; Out: hold, then animate out.
        ClipAnims.xf(anim, (t * durUs).toLong(), durUs, xf)
    } else xf.reset()
    Column(Modifier.width(66.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).clip(RoundedCornerShape(14.dp)).background(Amiri.SurfaceHigh)
                .border(if (selected) 2.dp else 1.dp, if (selected) Amiri.accentInk(accent) else Amiri.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Faint outline of the resting frame, so the motion reads against it (and the tile is never empty).
            if (spec != null) Box(
                Modifier.size(34.dp).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp)),
            )
            if (spec == null) Icon(Icons.Outlined.Block, null, tint = Amiri.TextSecondary, modifier = Modifier.size(24.dp))
            else Canvas(
                Modifier.size(34.dp).graphicsLayer {
                    translationX = xf.dx * 34.dp.toPx() * 0.9f
                    translationY = xf.dy * 34.dp.toPx() * 0.9f
                    scaleX = xf.sx; scaleY = xf.sy
                    rotationZ = xf.rot
                    alpha = xf.alpha.coerceIn(0f, 1f)
                },
            ) {
                // a tiny "photo": sky, sun and hills
                drawRoundRect(Brush.verticalGradient(listOf(accent, Amiri.accent2(accent))), cornerRadius = CornerRadius(size.width * 0.18f))
                drawCircle(Color.White.copy(alpha = 0.9f), size.width * 0.12f, Offset(size.width * 0.7f, size.height * 0.32f))
                val hill = Path().apply {
                    moveTo(0f, size.height * 0.8f)
                    cubicTo(size.width * 0.3f, size.height * 0.45f, size.width * 0.55f, size.height * 0.95f, size.width, size.height * 0.62f)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(hill, Color.White.copy(alpha = 0.55f))
            }
        }
        Text(
            label, color = if (selected) Amiri.accentInk(accent) else Amiri.TextSecondary, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp),
        )
    }
}

// ═══════════════════════════════ Stickers ═══════════════════════════════

private val EMOJI = linkedMapOf(
    "Cats" to listOf("🐱", "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿", "😾", "🐈", "🐈‍⬛", "🐾", "🦁", "🐯", "🐟"),
    "Smileys" to listOf("😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "🥳", "😜", "🤔", "😴", "😭", "😡", "🤯", "😇", "🫶"),
    "Love" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💖", "💘", "💕", "💞", "💋", "🌹", "💐", "💍"),
    "Party" to listOf("🎉", "🎊", "🎈", "🎂", "🎁", "✨", "🌟", "⭐", "🔥", "💥", "🎶", "🎵", "🏆", "👑", "💯", "🚀"),
    "Hands" to listOf("👍", "👏", "🙌", "🙏", "👌", "✌️", "🤞", "🤙", "👋", "💪", "👀", "☝️", "👉", "👈", "👆", "👇"),
    "Nature" to listOf("☀️", "🌙", "⛅", "🌈", "⚡", "❄️", "🌊", "🌸", "🌻", "🍀", "🌴", "🍂", "🌍", "🦋", "🐶", "🐼"),
    "Food" to listOf("☕", "🍕", "🍔", "🍟", "🍩", "🍦", "🍉", "🍓", "🍫", "🍿", "🧁", "🥤", "🍜", "🍣", "🥐", "🍎"),
    "Travel" to listOf("✈️", "🚗", "🏝️", "🏔️", "🗽", "🗼", "🎡", "⛺", "🧳", "📍", "🗺️", "📸", "🎬", "🎥", "💡", "⏰"),
)

private val MOTIONS = listOf("None" to "None", "float" to "Float", "pulse" to "Pulse", "rock" to "Rock", "wobble" to "Wobble", "heartbeat" to "Heartbeat", "shake" to "Shake", "spinLoop" to "Spin")

@Composable
internal fun StickersPanel(c: EditorController) {
    val view = LocalView.current
    var tab by remember { mutableStateOf("Amiri cats") }
    val sel = c.selectedClip()
    val isSticker = sel != null && (sel.name.startsWith("Sticker"))
    Column(Modifier.padding(horizontal = 6.dp)) {
        UnderlineTabs(listOf("Amiri cats") + EMOJI.keys.toList(), tab, { it }) { tab = it }
        if (tab == "Amiri cats") {
            GridRows(StickerArt.ITEMS, 4) { item, mod ->
                Column(mod.clip(RoundedCornerShape(14.dp)).clickable { Haptics.confirm(view); c.addArtSticker(item.id) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(Amiri.SurfaceHigh), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(50.dp)) { with(StickerArt) { drawSticker(item.id) } }
                    }
                    Text(item.label, color = Amiri.TextSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            GridRows(EMOJI[tab].orEmpty(), 8) { e, mod ->
                Box(
                    mod.padding(3.dp).clip(RoundedCornerShape(12.dp)).background(Amiri.SurfaceHigh)
                        .clickable { Haptics.confirm(view); c.addEmojiSticker(e) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 24.sp) }
            }
        }
        if (isSticker) {
            GroupLabel("Motion of the selected sticker")
            val cur = sel?.anim?.comboId ?: ClipAnims.NONE
            ChoiceChips(MOTIONS, MOTIONS.firstOrNull { it.first == cur } ?: MOTIONS[0], { it.second }) { m ->
                c.setAnim("Sticker motion: ${m.second}") { it.copy(comboId = m.first) }
            }
        } else {
            Text("Tap a sticker to add it at the playhead, then drag it on the picture to place it.", color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        }
    }
}

@Composable
private fun <T> GridRows(items: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Suppress("unused") private fun keep() = Modifier.fillMaxSize()
