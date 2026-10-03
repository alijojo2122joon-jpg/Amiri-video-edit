package com.amiri.cut.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.amiri.cut.storage.AccentChoice
import com.amiri.cut.storage.CacheManager
import com.amiri.cut.storage.PerformanceMode
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.BarRow
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.Gap
import com.amiri.cut.ui.common.GlassCard
import com.amiri.cut.ui.common.IconAction
import com.amiri.cut.ui.common.SectionLabel
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.LocalAccent
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AmiriCutApp, onBack: () -> Unit) {
    val s = app.settings
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheRefresh by remember { mutableIntStateOf(0) }

    val networkFree = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.none { it == "android.permission.INTERNET" } ?: true
        }.getOrDefault(false)
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            BarRow {
                IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Text("Settings", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column {
                    SectionLabel("Accent")
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AccentChoice.entries.forEach { a ->
                            val c = Color(a.argb)
                            Box(
                                Modifier.size(34.dp).background(c.copy(alpha = 0.85f), CircleShape)
                                    .border(if (a == s.accent) 2.dp else 1.dp, if (a == s.accent) Color.White else Color.White.copy(alpha = 0.15f), CircleShape)
                                    .clickable { s.updateAccent(a) },
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Performance")
                    ChoiceChips(PerformanceMode.entries.toList(), s.performance, { it.label }) { s.updatePerformance(it) }
                    Gap(h = 8)
                    Text(
                        "Sets how many background workers decode thumbnails and how dense the timeline filmstrip is. " +
                            "In later stages it also drives preview resolution and export encoder priority. Takes effect on next launch.",
                        color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    )
                }

                Column {
                    SectionLabel("Preview")
                    ChoiceChips(com.amiri.cut.storage.PreviewQuality.entries.toList(), s.previewQuality, { it.label }) { s.updatePreviewQuality(it) }
                    Gap(h = 6)
                    ToggleRow("Proxy mode (decode built proxies in preview)", s.proxyMode) { s.updateProxyMode(it) }
                    Text(
                        "Half/Quarter render the preview at lower resolution for heavy effects. Export always renders at full quality.",
                        color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    )
                }

                Column {
                    SectionLabel("Editing")
                    ToggleRow("Snapping on timeline", s.snapping) { s.updateSnapping(it) }
                    ToggleRow("Haptic feedback", s.haptics) { s.updateHaptics(it) }
                }

                Column {
                    SectionLabel("Cache manager")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CacheManager.Kind.entries.forEach { kind ->
                                val size by produceState(-1L, cacheRefresh) { value = app.caches.size(kind) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.label, color = Amiri.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (size < 0) "Measuring…" else CacheManager.formatBytes(size),
                                            color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    TextButton(onClick = { scope.launch { app.caches.clear(kind); cacheRefresh++ } }) {
                                        Text("Clear", color = accent)
                                    }
                                }
                            }
                            val total by produceState(-1L, cacheRefresh) {
                                value = CacheManager.Kind.entries.sumOf { app.caches.size(it) }
                            }
                            Text(
                                "Cache size: " + if (total < 0) "…" else CacheManager.formatBytes(total),
                                color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Caches are rebuilt automatically. Clearing never affects projects or your media.",
                                color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Privacy")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                if (networkFree) "Network access: none (verified)" else "Network permission present",
                                color = if (networkFree) accent else Amiri.Danger, style = MaterialTheme.typography.titleMedium,
                            )
                            Gap(h = 4)
                            Text(
                                "Amiri Cut has no internet permission, no accounts, no analytics and no cloud. " +
                                    "Projects and caches live only in this app's private storage; your media is read in place.",
                                color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Image(
                    painterResource(R.drawable.amiri_logo_full), "Amiri Cut logo",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)),
                )
                Text(
                    "Amiri Cut · ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""}",
                    color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Amiri.TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent.copy(alpha = 0.5f), checkedThumbColor = accent),
        )
    }
}
