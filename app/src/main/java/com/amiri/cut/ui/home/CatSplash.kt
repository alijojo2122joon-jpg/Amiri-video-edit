package com.amiri.cut.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.R
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.paw
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.CatSounds
import com.amiri.cut.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opening: little paws walk in, the logo pops up, a soft purr starts. Tap to skip. */
@Composable
fun CatSplash(onDone: () -> Unit) {
    val accent = LocalAccent.current
    val steps = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        CatSounds.purr(7f)
        launch { steps.animateTo(8f, tween(1100, easing = FastOutSlowInEasing)) }
        delay(700)
        launch { logo.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        delay(300)
        title.animateTo(1f, tween(450))
        delay(650)
        onDone()
    }
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        AmbientBackground()
        Canvas(Modifier.fillMaxSize()) {
            // A trail of paw prints walking up towards the logo.
            val n = 8
            for (i in 0 until n) {
                val a = (steps.value - i).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val f = i / (n - 1f)
                val x = size.width * (0.18f + 0.32f * f) + (if (i % 2 == 0) -18f else 18f)
                val y = size.height * (0.92f - 0.4f * f)
                paw(Offset(x, y), size.width * 0.07f, accent.copy(alpha = 0.55f * a * (0.5f + 0.5f * f)), 25f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.amiri_logo_mark), "Amiri Cut",
                modifier = Modifier.size(112.dp).graphicsLayer {
                    scaleX = logo.value; scaleY = logo.value; alpha = logo.value.coerceIn(0f, 1f)
                    rotationZ = (1f - logo.value) * -20f
                }.clip(RoundedCornerShape(30.dp)),
            )
            Text(
                "Amiri Cut", color = Amiri.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp).graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 30f },
            )
            Text(
                "made with 🐾", color = Amiri.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp).graphicsLayer { alpha = title.value },
            )
        }
    }
}
