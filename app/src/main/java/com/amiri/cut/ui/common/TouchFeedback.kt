package com.amiri.cut.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.amiri.cut.ui.theme.CatSounds
import com.amiri.cut.ui.theme.LocalAccent
import kotlin.random.Random

private class PawRipple(val pos: Offset, val start: Long, val rot: Float)

/**
 * Wraps the whole app: every touch leaves a little paw print that fades away, gives a
 * light tick vibration, and a tap plays a tiny mew. Touches are only observed, never consumed.
 */
@Composable
fun CatTouchFeedback(content: @Composable () -> Unit) {
    val ripples = remember { mutableStateListOf<PawRipple>() }
    var now by remember { mutableLongStateOf(0L) }
    val accent = LocalAccent.current
    LaunchedEffect(ripples.size) {
        while (ripples.isNotEmpty()) {
            withFrameMillis { _ ->
                val t = android.os.SystemClock.uptimeMillis()
                now = t
                ripples.removeAll { t - it.start > 650 }
            }
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                CatSounds.tick()
                val t0 = down.uptimeMillis
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!ch.pressed) {
                        if (!moved && ch.uptimeMillis - t0 < 450) {
                            CatSounds.mew()
                            ripples += PawRipple(down.position, android.os.SystemClock.uptimeMillis(), Random.nextFloat() * 50f - 25f)
                        }
                        break
                    }
                }
            }
        },
    ) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val s0 = 26.dp.toPx()
            for (r in ripples) {
                val age = ((now - r.start) / 650f).coerceIn(0f, 1f)
                val a = (1f - age) * 0.55f
                paw(r.pos + Offset(0f, -s0 * 0.1f), s0 * (0.7f + 0.5f * age), accent.copy(alpha = a), r.rot)
            }
        }
    }
}
