package com.amiri.cut.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One RBJ biquad section with independent state for two channels. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(2); private val z2 = DoubleArray(2)
    var bypass = true
        private set

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    /** kind 0 = low shelf, 1 = peaking, 2 = high shelf. */
    fun design(kind: Int, fs: Double, f0: Double, gainDb: Double, q: Double = 0.707) {
        bypass = abs(gainDb) < 0.05
        if (bypass) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * (f0 / fs).coerceIn(1e-4, 0.49)
        val cw = cos(w0); val sw = sin(w0)
        when (kind) {
            1 -> {
                val alpha = sw / (2 * q)
                set(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            else -> {
                val alpha = sw / 2 * sqrt(2.0)
                val sa = 2 * sqrt(a) * alpha
                if (kind == 0) set(
                    a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                    (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
                ) else set(
                    a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                    (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
                )
            }
        }
    }

    fun process(x: Float, ch: Int): Float {
        if (bypass) return x
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y.toFloat()
    }
}

/**
 * Per-clip sound shaping shared by preview and export: 3-band EQ (bass shelf 150 Hz,
 * mid peak 1.2 kHz, treble shelf 5 kHz), stereo pan and a soft "voice" presence boost.
 * Parameters are dB (−12…+12) and pan (−1…1).
 */
class AudioFxChain(private val fs: Double) {
    private val bass = Biquad(); private val mid = Biquad(); private val treble = Biquad()
    private var cb = Float.NaN; private var cm = Float.NaN; private var ct = Float.NaN
    var pan = 0f

    fun update(bassDb: Float, midDb: Float, trebleDb: Float, pan: Float) {
        if (bassDb != cb) { bass.design(0, fs, 150.0, bassDb.toDouble()); cb = bassDb }
        if (midDb != cm) { mid.design(1, fs, 1200.0, midDb.toDouble(), 0.9); cm = midDb }
        if (trebleDb != ct) { treble.design(2, fs, 5000.0, trebleDb.toDouble()); ct = trebleDb }
        this.pan = pan.coerceIn(-1f, 1f)
    }

    val active: Boolean get() = !bass.bypass || !mid.bypass || !treble.bypass || abs(pan) > 0.001f

    /** Processes one stereo frame in place. */
    fun process(lr: FloatArray) {
        var l = lr[0]; var r = lr[1]
        l = treble.process(mid.process(bass.process(l, 0), 0), 0)
        r = treble.process(mid.process(bass.process(r, 1), 1), 1)
        if (pan > 0f) l *= 1f - pan else if (pan < 0f) r *= 1f + pan
        lr[0] = l; lr[1] = r
    }
}
