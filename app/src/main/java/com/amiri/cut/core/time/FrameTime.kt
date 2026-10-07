package com.amiri.cut.core.time

import kotlin.math.roundToLong

/**
 * Frame-exact time math. Timeline positions are stored in µs but every edit is
 * quantized to frame boundaries of the project frame rate, so cuts always land
 * on real frames.
 */
object FrameTime {
    const val US_PER_SECOND = 1_000_000L

    fun frameDurationUs(fps: Int): Double = US_PER_SECOND.toDouble() / fps

    /** Nearest frame index for a time. */
    fun toFrame(us: Long, fps: Int): Long = (us.toDouble() * fps / US_PER_SECOND).roundToLong()

    /** Exact start time (floored µs) of a frame index. */
    fun fromFrame(frame: Long, fps: Int): Long = frame * US_PER_SECOND / fps

    /** Snap a time to the nearest frame boundary. */
    fun quantize(us: Long, fps: Int): Long = fromFrame(toFrame(us, fps), fps)

    /** Frame index containing [us] (floor) — used for display. */
    fun frameFloor(us: Long, fps: Int): Long = (us * fps) / US_PER_SECOND

    /** HH:MM:SS:FF (hours omitted when zero). */
    fun timecode(us: Long, fps: Int, alwaysHours: Boolean = false): String {
        val totalFrames = frameFloor(us.coerceAtLeast(0), fps)
        val ff = totalFrames % fps
        val totalSec = totalFrames / fps
        val ss = totalSec % 60
        val mm = (totalSec / 60) % 60
        val hh = totalSec / 3600
        return if (hh > 0 || alwaysHours) {
            "%02d:%02d:%02d:%02d".format(hh, mm, ss, ff)
        } else {
            "%02d:%02d:%02d".format(mm, ss, ff)
        }
    }

    /** Short "m:ss" label used for durations. */
    /** "00:07" / "1:02:07" — the playback clock shown next to the play button. */
    fun shortClock(us: Long): String {
        val s = (us.coerceAtLeast(0) / 1_000_000)
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun shortDuration(us: Long): String {
        val totalSec = us / US_PER_SECOND
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
