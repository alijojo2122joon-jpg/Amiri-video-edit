package com.amiri.cut.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Records the microphone to a 48 kHz mono 16-bit WAV file (offline, no compression). */
class VoiceRecorder(private val file: File, private val onLevel: (Float) -> Unit) {
    private val rate = 48_000
    @Volatile private var running = false
    private var thread: Thread? = null
    var error: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { error = "Microphone not available"; return false }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5))
        } catch (t: Throwable) { error = t.message; return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error = "Microphone busy"; return false }
        file.parentFile?.mkdirs()
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(ByteArray(44))
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(rate / 25)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0L
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var peak = 0
                    for (i in 0 until n) { bytes.putShort(buf[i]); peak = max(peak, abs(buf[i].toInt())) }
                    out.write(bytes.array(), 0, n * 2)
                    total += n * 2
                    onLevel(peak / 32768f)
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                writeHeader(out, total)
                out.close()
            }
        }.apply { name = "VoiceRecorder"; start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    private fun writeHeader(f: RandomAccessFile, dataLen: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        f.seek(0); f.write(b.array())
    }
}
