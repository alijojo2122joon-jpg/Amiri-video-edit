package com.amiri.cut.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.R
import com.amiri.cut.storage.AccentChoice
import com.amiri.cut.storage.CacheManager
import com.amiri.cut.storage.PerformanceMode
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.DiscButton
import com.amiri.cut.ui.common.GroupLabel
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AmiriCutApp, onBack: () -> Unit) {
    val s = app.settings
    val accent = LocalAccent.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var cacheRefresh by remember { mutableIntStateOf(0) }

    val networkFree = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.none { it == "android.permission.INTERNET" } ?: true
        }.getOrDefault(false)
    }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                DiscButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Text("Settings", color = Amiri.TextPrimary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 14.dp))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            ) {
                GroupLabel("Appearance")
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Accent colour", color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(s.accent.label, color = Amiri.TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AccentChoice.entries.forEach { a ->
                            val c = Color(a.argb)
                            val on = a == s.accent
                            Box(
                                Modifier.size(34.dp).clip(CircleShape)
                                    .clickable { Haptics.select(view); s.updateAccent(a) }
                                    .border(2.dp, if (on) Color.White else Color.Transparent, CircleShape)
                                    .padding(if (on) 4.dp else 0.dp).clip(CircleShape).background(c),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Icon(Icons.Outlined.Check, null, tint = if (c.luminance() > 0.5f) Color(0xFF111114) else Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                GroupLabel("Preview")
                Card {
                    Text("Preview resolution", color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    ChoiceChips(com.amiri.cut.storage.PreviewQuality.entries.toList(), s.previewQuality, { it.label }) { s.updatePreviewQuality(it) }
                    Spacer(Modifier.height(8.dp))
                    Hint("Lower preview resolution keeps playback smooth with heavy effects. Export always renders at full quality.")
                    Divider()
                    ToggleRow("Proxy mode", "Plays light 960-px copies of clips you've built proxies for", s.proxyMode) { s.updateProxyMode(it) }
                }

                GroupLabel("Editing")
                Card {
                    ToggleRow("Snapping", "Clips snap to the playhead and to each other", s.snapping) { s.updateSnapping(it) }
                    Divider()
                    ToggleRow("Haptic feedback", null, s.haptics) { s.updateHaptics(it) }
                    Divider()
                    ToggleRow("Tiny mew on taps 🐾", null, s.catSounds) { s.updateCatSounds(it) }
                    Divider()
                    ToggleRow("Soft purr on the home screen", null, s.purr) { s.updatePurr(it) }
                }

                GroupLabel("Performance")
                Card {
                    ChoiceChips(PerformanceMode.entries.toList(), s.performance, { it.label }) { s.updatePerformance(it) }
                    Spacer(Modifier.height(8.dp))
                    Hint("How many background workers build thumbnails and how dense the timeline filmstrip is. Applies on next launch.")
                }

                GroupLabel("Storage")
                Card {
                    CacheManager.Kind.entries.forEachIndexed { i, kind ->
                        val size by produceState(-1L, cacheRefresh) { value = app.caches.size(kind) }
                        if (i > 0) Divider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(kind.label, color = Amiri.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                                Text(if (size < 0) "Measuring…" else CacheManager.formatBytes(size), color = Amiri.TextTertiary, fontSize = 12.sp, style = MonoStyle)
                            }
                            Text(
                                "Clear", color = Amiri.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Amiri.SurfaceTop)
                                    .clickable { Haptics.select(view); scope.launch { app.caches.clear(kind); cacheRefresh++ } }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Divider()
                    val total by produceState(-1L, cacheRefresh) { value = CacheManager.Kind.entries.sumOf { app.caches.size(it) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total cache", color = Amiri.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (total < 0) "…" else CacheManager.formatBytes(total), color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = MonoStyle)
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint("Caches rebuild automatically. Clearing never touches your projects or media.")
                }

                GroupLabel("Privacy")
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (networkFree) Amiri.Success.copy(alpha = 0.14f) else Amiri.Danger.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.WifiOff, null, tint = if (networkFree) Amiri.Success else Amiri.Danger, modifier = Modifier.size(20.dp)) }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                if (networkFree) "Works 100% offline" else "Network permission present",
                                color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (networkFree) "No internet permission — checked on this device" else "This build can reach the network",
                                color = if (networkFree) Amiri.Success else Amiri.Danger, fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Hint("No accounts, analytics or cloud. Projects and caches stay in the app's private storage; your media is read where it is.")
                }

                Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Image(
                        painterResource(R.drawable.amiri_logo_mark), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Amiri Cut $version", color = Amiri.TextTertiary, fontSize = 12.5.sp, style = MonoStyle)
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Amiri.Surface)
            .border(1.dp, Amiri.Line, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Amiri.Line))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Amiri.TextTertiary, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
private fun ToggleRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
            Haptics.select(view); onChange(!checked)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = Amiri.TextTertiary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked, onCheckedChange = { Haptics.select(view); onChange(it) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Amiri.SurfaceTop, uncheckedThumbColor = Amiri.TextSecondary, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
