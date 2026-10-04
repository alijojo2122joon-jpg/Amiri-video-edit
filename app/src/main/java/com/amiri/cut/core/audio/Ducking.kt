package com.amiri.cut.core.audio

import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.TrackKind

/**
 * Auto-ducking: a clip with "duck" > 0 gets quieter while a voice clip plays (a clip whose
 * audio "voice" flag is on, or any clip on a track named "Voice…"), with smooth 0.3 s ramps.
 */
object Ducking {
    private const val RAMP = 300_000L

    fun isVoice(p: Project, c: Clip): Boolean =
        c.audio.at("voice", 0, 0f) > 0.5f || p.trackOfClip(c.id)?.let { it.kind == TrackKind.AUDIO && it.name.startsWith("Voice") } == true

    fun factor(p: Project, clip: Clip, t: Long): Float {
        val amt = clip.audio.at("duck", t - clip.startUs, 0f)
        if (amt <= 0.001f) return 1f
        var env = 0f
        for (tr in p.tracks) {
            if (tr.muted) continue
            for (c in tr.clips) {
                if (c.id == clip.id || c.muted) continue
                if (t < c.startUs - RAMP || t > c.endUs + RAMP) continue
                if (!isVoice(p, c)) continue
                val e = when {
                    t < c.startUs -> 1f - (c.startUs - t).toFloat() / RAMP
                    t > c.endUs -> 1f - (t - c.endUs).toFloat() / RAMP
                    else -> 1f
                }
                if (e > env) env = e
            }
        }
        return 1f - amt.coerceIn(0f, 1f) * 0.85f * env.coerceIn(0f, 1f)
    }
}
