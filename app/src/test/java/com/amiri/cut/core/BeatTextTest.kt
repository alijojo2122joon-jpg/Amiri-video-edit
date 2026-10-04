package com.amiri.cut.core

import com.amiri.cut.core.audio.BeatMath
import com.amiri.cut.core.model.TextSpec
import com.amiri.cut.core.text.TextAnims
import com.amiri.cut.core.text.UnitXf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatTextTest {
    @Test fun findsTempoOfClickTrack() {
        val hop = 512.0 / 44100
        val n = (20.0 / hop).toInt()
        val env = FloatArray(n)
        // 120 BPM clicks starting at 0.25 s
        var t = 0.25
        while (t < 20.0) { env[(t / hop).toInt()] = 1f; t += 0.5 }
        val r = BeatMath.detect(env, hop, BeatMath.Mode.BEATS)
        assertEquals(120.0, r.bpm, 4.0)
        assertTrue(r.times.size in 36..42)
        assertEquals(0.25, r.times.first(), 0.03)
        val bars = BeatMath.detect(env, hop, BeatMath.Mode.BARS)
        assertTrue(bars.times.size in 9..11)
    }

    @Test fun hitsAreSpacedAndStrong() {
        val hop = 0.01
        val env = FloatArray(1000) { 0.01f }
        env[100] = 1f; env[105] = 0.9f; env[500] = 1f
        val h = BeatMath.hits(env, hop)
        assertEquals(2, h.size)
    }

    @Test fun staggeredUnitsFinishTogether() {
        for (i in 0 until 5) {
            assertEquals(1f, TextAnims.unitProgress(1f, i, 5, 0.35f), 1e-4f)
            assertEquals(0f, TextAnims.unitProgress(0f, i, 5, 0.35f), 1e-4f)
        }
        assertTrue(TextAnims.unitProgress(0.3f, 0, 5, 0.35f) > TextAnims.unitProgress(0.3f, 4, 5, 0.35f))
        // Typewriter: one after another
        assertEquals(1f, TextAnims.unitProgress(0.5f, 1, 4, 0f), 1e-4f)
        assertEquals(0f, TextAnims.unitProgress(0.5f, 2, 4, 0f), 1e-4f)
    }

    @Test fun layerAnimRestsAfterEntrance() {
        val s = TextSpec(animIn = "slideUp", animOut = "fade", inDur = 0.5f, outDur = 0.5f)
        val start = TextAnims.layerXf(s, 0, 3_000_000)
        assertEquals(0f, start.alpha, 1e-4f)
        assertTrue(start.dy > 1f)
        val mid = TextAnims.layerXf(s, 1_500_000, 3_000_000, UnitXf())
        assertTrue(mid.identity)
        val end = TextAnims.layerXf(s, 2_999_000, 3_000_000, UnitXf())
        assertTrue(end.alpha < 0.05f)
    }
}
