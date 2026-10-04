package com.amiri.cut.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.amiri.cut.core.audio.AudioFxChain
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Applies the clip's EQ / pan (same DSP as export) inside an ExoPlayer audio sink. */
@OptIn(UnstableApi::class)
class ClipAudioProcessor : BaseAudioProcessor() {
    @Volatile private var params = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
    private var chain: AudioFxChain? = null
    private var channels = 2
    private val lr = FloatArray(2)

    fun set(bass: Float, mid: Float, treble: Float, pan: Float, denoise: Float = 0f, enhance: Float = 0f) {
        val p = params
        if (p[0] != bass || p[1] != mid || p[2] != treble || p[3] != pan || p[4] != denoise || p[5] != enhance) params = floatArrayOf(bass, mid, treble, pan, denoise, enhance)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        chain = AudioFxChain(inputAudioFormat.sampleRate.toDouble())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val ch = chain
        val p = params
        ch?.update(p[0], p[1], p[2], p[3], p[4], p[5])
        if (ch == null || !ch.active) {
            out.put(inputBuffer)
        } else {
            val inp = inputBuffer.order(ByteOrder.nativeOrder())
            val frames = size / (2 * channels)
            for (f in 0 until frames) {
                val l = inp.getShort() / 32768f
                val r = if (channels == 2) inp.getShort() / 32768f else l
                lr[0] = l; lr[1] = r
                ch.process(lr)
                out.putShort((lr[0].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                if (channels == 2) out.putShort((lr[1].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            inputBuffer.position(inputBuffer.limit())
        }
        out.flip()
    }

    override fun onFlush() { chain = chain?.let { AudioFxChain(inputAudioFormat.sampleRate.toDouble()) } }
}
