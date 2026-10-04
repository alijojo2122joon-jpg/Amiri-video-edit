package com.amiri.cut.media

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI voice isolation (on-device, offline): a dual-signal LSTM network (DTLN, MIT
 * licence, trained on hundreds of hours of noisy speech) separates the human voice from
 * wind, traffic, crowd, hum and room noise. It works at 16 kHz in 32 ms frames with
 * 8 ms hops; a low-cut removes wind rumble first. "Strong" runs the network twice.
 */
class VoiceIsolation(context: Context) : AutoCloseable {
    private val m1: Interpreter
    private val m2: Interpreter
    private val magIn1: Int; private val stIn1: Int; private val maskOut1: Int; private val stOut1: Int
    private val blkIn2: Int; private val stIn2: Int; private val blkOut2: Int; private val stOut2: Int

    init {
        fun load(name: String): ByteBuffer {
            val bytes = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
        val opts = Interpreter.Options().setNumThreads(2)
        m1 = Interpreter(load("models/dtln_1.tflite"), opts)
        m2 = Interpreter(load("models/dtln_2.tflite"), opts)
        fun last(i: Interpreter, input: Boolean, idx: Int) = (if (input) i.getInputTensor(idx) else i.getOutputTensor(idx)).shape().last()
        magIn1 = if (last(m1, true, 0) == 257) 0 else 1; stIn1 = 1 - magIn1
        maskOut1 = if (last(m1, false, 0) == 257) 0 else 1; stOut1 = 1 - maskOut1
        blkIn2 = if (last(m2, true, 0) == 512) 0 else 1; stIn2 = 1 - blkIn2
        blkOut2 = if (last(m2, false, 0) == 512) 0 else 1; stOut2 = 1 - blkOut2
    }

    private fun stateBuf(i: Interpreter, idx: Int): ByteBuffer {
        val n = i.getInputTensor(idx).shape().fold(1) { a, b -> a * b }
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
    }

    /** Denoises 16 kHz mono speech. */
    fun process16k(x: FloatArray, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): FloatArray {
        val len = 512; val shift = 128
        val out = FloatArray(x.size + len)
        val inBuf = FloatArray(len)
        val outBuf = FloatArray(len)
        var st1 = stateBuf(m1, stIn1); var st1o = stateBuf(m1, stIn1)
        var st2 = stateBuf(m2, stIn2); var st2o = stateBuf(m2, stIn2)
        val mag = ByteBuffer.allocateDirect(257 * 4).order(ByteOrder.nativeOrder())
        val mask = ByteBuffer.allocateDirect(257 * 4).order(ByteOrder.nativeOrder())
        val blk = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder())
        val blkOut = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder())
        val re = DoubleArray(len); val im = DoubleArray(len)
        val magF = FloatArray(257); val ph = FloatArray(257)
        val blocks = (x.size + len) / shift
        for (b in 0 until blocks) {
            if (isCancelled()) break
            System.arraycopy(inBuf, shift, inBuf, 0, len - shift)
            for (k in 0 until shift) { val i = b * shift + k; inBuf[len - shift + k] = if (i < x.size) x[i] else 0f }
            for (k in 0 until len) { re[k] = inBuf[k].toDouble(); im[k] = 0.0 }
            Fft.fft(re, im, false)
            mag.clear()
            for (k in 0..256) { val m = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat(); magF[k] = m; ph[k] = atan2(im[k], re[k]).toFloat(); mag.putFloat(m) }
            mag.rewind(); st1.rewind(); mask.clear(); st1o.clear()
            val in1 = arrayOfNulls<Any>(2); in1[magIn1] = mag; in1[stIn1] = st1
            m1.runForMultipleInputsOutputs(in1, mapOf(maskOut1 to mask, stOut1 to st1o))
            val tmp1 = st1; st1 = st1o; st1o = tmp1
            mask.rewind()
            for (k in 0..256) {
                val m = magF[k] * mask.float
                re[k] = (m * cos(ph[k])).toDouble(); im[k] = (m * sin(ph[k])).toDouble()
                if (k in 1..255) { re[len - k] = re[k]; im[len - k] = -im[k] }
            }
            Fft.fft(re, im, true)
            blk.clear(); for (k in 0 until len) blk.putFloat(re[k].toFloat())
            blk.rewind(); st2.rewind(); blkOut.clear(); st2o.clear()
            val in2 = arrayOfNulls<Any>(2); in2[blkIn2] = blk; in2[stIn2] = st2
            m2.runForMultipleInputsOutputs(in2, mapOf(blkOut2 to blkOut, stOut2 to st2o))
            val tmp2 = st2; st2 = st2o; st2o = tmp2
            blkOut.rewind()
            System.arraycopy(outBuf, shift, outBuf, 0, len - shift)
            for (k in len - shift until len) outBuf[k] = 0f
            for (k in 0 until len) outBuf[k] += blkOut.float
            for (k in 0 until shift) { val i = b * shift + k; if (i < out.size) out[i] = outBuf[k] }
            if (b % 50 == 0) onProgress(b.toFloat() / blocks)
        }
        // The network delays the signal by one block minus one hop.
        val delay = len - shift
        return FloatArray(x.size) { i -> val j = i + delay; if (j < out.size) out[j] else 0f }
    }

    override fun close() { m1.close(); m2.close() }

    companion object {
        /**
         * Full pipeline: low-cut (wind rumble) → 16 kHz → network (1 or 2 passes) → back to
         * [rate]; [strength] blends with the original (1 = voice only).
         */
        fun clean(context: Context, x: FloatArray, rate: Int, strength: Float, passes: Int, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): FloatArray {
            // 2nd-order high-pass at 80 Hz (wind rumble, handling noise).
            val hp = run {
                val w0 = 2 * PI * 80.0 / rate; val alpha = sin(w0) / (2 * 0.707); val c = cos(w0)
                val a0 = 1 + alpha
                doubleArrayOf((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
            val y = FloatArray(x.size)
            var z1 = 0.0; var z2 = 0.0
            for (i in x.indices) { val o = hp[0] * x[i] + z1; z1 = hp[1] * x[i] - hp[3] * o + z2; z2 = hp[2] * x[i] - hp[4] * o; y[i] = o.toFloat() }
            var s16 = AudioDecode.resample(y, rate, 16_000)
            VoiceIsolation(context).use { vi ->
                for (pass in 0 until passes) {
                    s16 = vi.process16k(s16, isCancelled) { f -> onProgress((pass + f) / passes) }
                }
            }
            val wet = AudioDecode.resample(s16, 16_000, rate)
            val s = strength.coerceIn(0f, 1f)
            return FloatArray(x.size) { i -> (if (i < wet.size) wet[i] else 0f) + (1f - s) * x[i] }
        }
    }
}

/** In-place radix-2 FFT (size must be a power of two). */
object Fft {
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
