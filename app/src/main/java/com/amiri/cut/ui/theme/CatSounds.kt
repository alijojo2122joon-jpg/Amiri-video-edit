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
    /** Last time the screen was touched (uptime ms) — the walking cat runs away. */
    @Volatile var lastTouchMs = 0L

    private var pool: SoundPool? = null
    private val mews = IntArray(4)
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
        appContext = context.applicationContext
        pool = p
        vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }.getOrNull()
    }

    /** A tiny tick vibration (touch down). */
    fun tick() {
        lastTouchMs = android.os.SystemClock.uptimeMillis()
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

    private var appContext: Context? = null
    private var player: android.media.MediaPlayer? = null
    private var purrIndex = 0
    private var purrGen = 0

    /** Soft real purring in the background (home / new project), looping gently between two recordings. */
    fun startPurr() {
        if (!soundsOn || !purrOn || inEditor) return
        if (player != null) return
        val ctx = appContext ?: return
        val gen = ++purrGen
        fun playNext() {
            if (gen != purrGen) return
            val res = if (purrIndex++ % 2 == 0) R.raw.purr_a else R.raw.purr_b
            val mp = runCatching { android.media.MediaPlayer.create(ctx, res) }.getOrNull() ?: return
            mp.setVolume(0f, 0f)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; main.postDelayed({ if (gen == purrGen && player == null) { playNext() } }, 1500) }
            player = mp
            mp.start()
            val peak = 0.2f
            for (i2 in 0..30) main.postDelayed({ if (player === mp && gen == purrGen) runCatching { mp.setVolume(peak * i2 / 30, peak * i2 / 30) } }, i2 * 60L)
        }
        playNext()
    }

    /** Kept for the splash screen. */
    fun purr(@Suppress("UNUSED_PARAMETER") seconds: Float = 7f) = startPurr()

    fun stopPurr() {
        purrGen++
        val mp = player ?: return
        player = null
        for (i in 0..10) main.postDelayed({
            runCatching { mp.setVolume(0.2f * (10 - i) / 10, 0.2f * (10 - i) / 10) }
            if (i == 10) runCatching { mp.stop(); mp.release() }
        }, i * 50L)
    }
}
