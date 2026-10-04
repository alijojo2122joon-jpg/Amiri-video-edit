package com.amiri.cut.storage

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PerformanceMode(val label: String, val workers: Int, val thumbsPerMinute: Int) {
    BATTERY_SAVER("Battery Saver", 1, 20),
    BALANCED("Balanced", 2, 40),
    PERFORMANCE("Performance", 3, 60),
    MAXIMUM("Maximum Performance", 4, 90),
}

enum class PreviewQuality(val label: String, val scale: Float) { FULL("Full", 1f), HALF("Half", 0.5f), QUARTER("Quarter", 0.25f) }

/** Accent colours, named after cat coats and eyes 🐾. */
enum class AccentChoice(val label: String, val argb: Long) {
    GINGER("Ginger", 0xFFF4A261),
    AMBER("Tabby Honey", 0xFFF2C47E),
    ROSE("Pink Nose", 0xFFF2A0B4),
    ICE("Russian Blue", 0xFF9FC3FF),
    MINT("Green Eyes", 0xFF8FE3C4),
    VIOLET("Lilac Point", 0xFFB9A6FF),
    MONO("Tuxedo", 0xFFE6E6EA),
}

/** App-wide settings, persisted in SharedPreferences and exposed as Compose state. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("amiri_settings", Context.MODE_PRIVATE)

    var accent by mutableStateOf(
        runCatching { AccentChoice.valueOf(prefs.getString("accent", AccentChoice.GINGER.name)!!) }.getOrDefault(AccentChoice.GINGER)
    )
        private set

    var performance by mutableStateOf(
        runCatching { PerformanceMode.valueOf(prefs.getString("performance", PerformanceMode.BALANCED.name)!!) }.getOrDefault(PerformanceMode.BALANCED)
    )
        private set

    var snapping by mutableStateOf(prefs.getBoolean("snapping", true))
        private set

    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set

    var previewQuality by mutableStateOf(
        runCatching { PreviewQuality.valueOf(prefs.getString("previewQuality", PreviewQuality.FULL.name)!!) }.getOrDefault(PreviewQuality.FULL)
    )
        private set

    /** Use low-resolution proxy files (when generated) for preview decoding. */
    var proxyMode by mutableStateOf(prefs.getBoolean("proxyMode", false))
        private set

    fun updatePreviewQuality(q: PreviewQuality) { previewQuality = q; prefs.edit().putString("previewQuality", q.name).apply() }
    fun updateProxyMode(on: Boolean) { proxyMode = on; prefs.edit().putBoolean("proxyMode", on).apply() }

    fun updateAccent(a: AccentChoice) { accent = a; prefs.edit().putString("accent", a.name).apply() }
    fun updatePerformance(m: PerformanceMode) { performance = m; prefs.edit().putString("performance", m.name).apply() }
    fun updateSnapping(on: Boolean) { snapping = on; prefs.edit().putBoolean("snapping", on).apply() }
    fun updateHaptics(on: Boolean) { haptics = on; prefs.edit().putBoolean("haptics", on).apply() }
}
