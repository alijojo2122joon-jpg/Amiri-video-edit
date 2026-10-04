package com.amiri.cut.core.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Beat / hit detection from an onset-strength envelope (pure math, testable). */
object BeatMath {
    enum class Mode(val label: String) { BEATS("Beats"), HALF("Every 2nd beat"), BARS("Every bar (4)"), HITS("Strong hits") }

    class Result(val times: List<Double>, val bpm: Double)

    /** Onset strength from frame energies: positive change of log energy (low band weighted). */
    fun onsets(full: FloatArray, low: FloatArray): FloatArray {
        val n = full.size
        val out = FloatArray(n)
        var pf = 0.0; var pl = 0.0
        for (i in 0 until n) {
            val lf = ln(1e-7 + full[i]); val ll = ln(1e-7 + low[i])
            if (i > 0) out[i] = (0.4 * max(0.0, lf - pf) + 0.6 * max(0.0, ll - pl)).toFloat()
            pf = lf; pl = ll
        }
        // Remove the local mean so steady loud parts don't count.
        val w = 16
        val res = FloatArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += out[i]
            if (i >= w) acc -= out[i - w]
            val mean = acc / min(i + 1, w)
            res[i] = max(0.0, out[i] - mean).toFloat()
        }
        return res
    }

    fun detect(env: FloatArray, hopSec: Double, mode: Mode): Result {
        val n = env.size
        if (n < 16) return Result(emptyList(), 0.0)
        if (mode == Mode.HITS) return Result(hits(env, hopSec), 0.0)
        // Tempo by autocorrelation (60..190 BPM), preferring ~120 BPM.
        val minLag = max(2, (60.0 / 190.0 / hopSec).toInt())
        val maxLag = min(n / 2, (60.0 / 60.0 / hopSec).toInt())
        var bestLag = -1; var best = -1.0
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until n - lag) s += env[i] * env[i + lag]
            ac[lag] = s / (n - lag)
            val bpm = 60.0 / (lag * hopSec)
            val wgt = exp(-0.5 * (ln(bpm / 120.0) / 0.5).let { it * it })
            if (ac[lag] * wgt > best) { best = ac[lag] * wgt; bestLag = lag }
        }
        if (bestLag <= 0) return Result(emptyList(), 0.0)
        var period = bestLag.toDouble()
        if (bestLag in (minLag + 1) until maxLag) {
            val l = ac[bestLag - 1]; val c = ac[bestLag]; val r = ac[bestLag + 1]
            val d = l - 2 * c + r
            if (d < 0) period += 0.5 * (l - r) / d
        }
        // Phase: the offset whose beat grid collects the most onset energy.
        var bestPhase = 0; var bestScore = -1.0
        for (ph in 0 until period.toInt().coerceAtLeast(1)) {
            var s = 0.0; var k = 0
            while (true) { val i = (ph + k * period).toInt(); if (i >= n) break; s += env[i]; k++ }
            if (s > bestScore) { bestScore = s; bestPhase = ph }
        }
        val times = ArrayList<Double>()
        var k = 0
        val win = max(1, (period * 0.12).toInt())
        while (true) {
            val c = (bestPhase + k * period).toInt()
            if (c >= n) break
            var bi = c; var bv = -1f
            for (i in max(0, c - win)..min(n - 1, c + win)) if (env[i] > bv) { bv = env[i]; bi = i }
            times += bi * hopSec
            k++
        }
        val step = when (mode) { Mode.HALF -> 2; Mode.BARS -> 4; else -> 1 }
        return Result(times.filterIndexed { i, _ -> i % step == 0 }, 60.0 / (period * hopSec))
    }

    /** Strong individual hits: peaks well above the local level, at least 120 ms apart. */
    fun hits(env: FloatArray, hopSec: Double): List<Double> {
        val n = env.size
        val w = max(4, (1.0 / hopSec).toInt())
        val out = ArrayList<Double>()
        var last = -1e9
        for (i in 1 until n - 1) {
            if (env[i] < env[i - 1] || env[i] < env[i + 1]) continue
            var s = 0.0; var s2 = 0.0; var c = 0
            for (j in max(0, i - w)..min(n - 1, i + w)) { s += env[j]; s2 += env[j] * env[j]; c++ }
            val m = s / c; val sd = sqrt(max(0.0, s2 / c - m * m))
            val t = i * hopSec
            if (env[i] > m + 1.5 * sd && env[i] > 1e-4 && t - last >= 0.12) { out += t; last = t }
        }
        return out
    }
}
