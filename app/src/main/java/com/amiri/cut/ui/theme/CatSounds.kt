package com.amiri.cut.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.amiri.cut.R
import kotlin.random.Random

/**
 * Amiri Cut's little cat sounds (synthesized originals bundled with the app): a tiny
 * mew on taps, a soft purr when the app opens, and a light tick vibration on touch.
 * Everything is very quiet and can be switched off in Settings.
 */
object CatSounds {
    @Volatile var soundsOn = true
    @Volatile var purrOn = true
    @Volatile var hapticsOn = true
    /** In the editor the app stays silent (only the tick vibration remains). */
    @Volatile var inEditor = false

    private var pool: SoundPool? = null
    private val mews = IntArray(4)
    private var purr = 0
    private var purrStream = 0
    private var lastMew = 0L
    private var vibrator: Vibrator? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        mews[0] = p.load(context, R.raw.cat_mew1, 1)
        mews[1] = p.load(context, R.raw.cat_mew2, 1)
        mews[2] = p.load(context, R.raw.cat_mew3, 1)
        mews[3] = p.load(context, R.raw.cat_mrrp, 1)
        purr = p.load(context, R.raw.cat_purr, 1)
        pool = p
        vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }.getOrNull()
    }

    /** A tiny tick vibration (touch down). */
    fun tick() {
        if (!hapticsOn) return
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else v.vibrate(VibrationEffect.createOneShot(10, 60))
        }
    }

    /** A very small mew (tap). Throttled so fast tapping doesn't turn into noise. */
    fun mew() {
        if (!soundsOn || inEditor) return
        val p = pool ?: return
        val now = System.currentTimeMillis()
        if (now - lastMew < 260) return
        lastMew = now
        val id = mews[Random.nextInt(mews.size)]
        val rate = 0.92f + Random.nextFloat() * 0.22f
        p.play(id, 0.16f, 0.16f, 1, 0, rate)
    }

    /** Soft purr in the background: fades in, purrs for a while, fades out. */
    fun purr(seconds: Float = 7f) {
        if (!soundsOn || !purrOn || inEditor) return
        val p = pool ?: return
        // The sample may still be loading on first start: try a few times.
        fun attempt(left: Int) {
            val s = p.play(purr, 0f, 0f, 0, 0, 1f)
            if (s == 0) { if (left > 0) main.postDelayed({ attempt(left - 1) }, 250); return }
            purrStream = s
            val steps = 30
            val peak = 0.14f
            for (i in 0..steps) main.postDelayed({ if (purrStream == s) p.setVolume(s, peak * i / steps, peak * i / steps) }, i * 50L)
            val outAt = (seconds * 1000).toLong() - 1500
            for (i in 0..steps) main.postDelayed({ if (purrStream == s) p.setVolume(s, peak * (steps - i) / steps, peak * (steps - i) / steps) }, outAt + i * 50L)
        }
        attempt(8)
    }

    fun stopPurr() {
        val p = pool ?: return
        val s = purrStream
        if (s == 0) return
        purrStream = 0
        for (i in 0..10) main.postDelayed({ p.setVolume(s, 0.14f * (10 - i) / 10, 0.14f * (10 - i) / 10); if (i == 10) p.stop(s) }, i * 40L)
    }
}
