package com.amiri.cut.engine

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.core.timeline.TimelineOps
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Stage-1 preview engine.
 *
 * The timeline owns the clock. Media3 ExoPlayer instances (hardware MediaCodec
 * decoders) are "slaves" that are loaded, seeked and started to match the
 * timeline position:
 *
 *  - one video player renders the top-most visible visual clip
 *  - one audio player per audio track plays that track's clip
 *
 * While a video clip is playing, the clock follows the video player's own
 * position (so picture never drifts); in gaps and on stills it runs on the
 * system monotonic clock. Contiguous clips cut from the same source (e.g. after
 * Split) continue without re-seeking.
 *
 * Every ExoPlayer here can later receive the same Media3 GL video effects used by
 * the Transformer export (Stage 7/11), which is how Preview ≈ Final Render.
 *
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class PreviewEngine(private val context: Context) {

    sealed interface Visual {
        data object None : Visual
        data class Video(val clipId: String, val assetId: String) : Visual
        data class Image(val asset: MediaAsset) : Visual
    }

    private class Slave(val player: ExoPlayer) {
        var uri: String? = null
        var clipId: String? = null
    }

    private val scope = MainScope()

    private val video = Slave(newPlayer())
    val videoPlayer: ExoPlayer get() = video.player
    private val audio = HashMap<String, Slave>()

    private var project: Project? = null

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _visual = MutableStateFlow<Visual>(Visual.None)
    val visual: StateFlow<Visual> = _visual

    private var clockJob: Job? = null
    private var baseUs = 0L
    private var baseNanos = 0L
    private var pendingScrub = false

    init {
        video.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && pendingScrub && !_playing.value) {
                    pendingScrub = false
                    sync(_position.value, playing = false)
                }
            }
        })
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setSeekParameters(SeekParameters.EXACT)
        }

    // ───────────────────────── public API ─────────────────────────

    fun setProject(p: Project) {
        project = p
        if (_position.value > p.durationUs) _position.value = p.durationUs
        sync(_position.value, _playing.value)
    }

    fun togglePlay() = if (_playing.value) pause() else play()

    fun play() {
        val p = project ?: return
        if (p.durationUs <= 0) return
        val frame = FrameTime.fromFrame(1, p.settings.fps)
        if (_position.value >= p.durationUs - frame) _position.value = 0
        _playing.value = true
        rebase(_position.value)
        sync(_position.value, playing = true)
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive && _playing.value) {
                tick()
                delay(8)
            }
        }
    }

    fun pause() {
        if (!_playing.value) return
        _playing.value = false
        clockJob?.cancel()
        clockJob = null
        val p = project
        // Land on an exact frame so edits made while paused are frame-accurate.
        val q = if (p != null) FrameTime.quantize(_position.value, p.settings.fps) else _position.value
        _position.value = q
        sync(q, playing = false)
    }

    /** Moves the playhead. While paused the preview shows the exact frame (latest-wins scrubbing). */
    fun seekTo(us: Long) {
        val p = project ?: return
        val q = FrameTime.quantize(us.coerceIn(0, p.durationUs), p.settings.fps)
        _position.value = q
        if (_playing.value) {
            rebase(q)
            sync(q, playing = true, forceSeek = true)
            return
        }
        if (video.uri != null && video.player.playbackState == Player.STATE_BUFFERING) {
            pendingScrub = true
        } else {
            sync(q, playing = false)
        }
    }

    fun stepFrames(delta: Int) {
        val p = project ?: return
        if (_playing.value) pause()
        val f = FrameTime.toFrame(_position.value, p.settings.fps) + delta
        seekTo(FrameTime.fromFrame(f.coerceAtLeast(0), p.settings.fps))
    }

    fun release() {
        clockJob?.cancel()
        scope.cancel()
        video.player.release()
        audio.values.forEach { it.player.release() }
        audio.clear()
    }

    // ───────────────────────── clock ─────────────────────────

    private fun rebase(us: Long) {
        baseUs = us
        baseNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun tick() {
        val p = project ?: return
        var pos = baseUs + (SystemClock.elapsedRealtimeNanos() - baseNanos) / 1000
        val activeClip = video.clipId?.let { id -> p.clip(id) }
        if (activeClip != null && _visual.value is Visual.Video) {
            val state = video.player.playbackState
            if (state == Player.STATE_BUFFERING) {
                // Hold the clock until the decoder delivers frames.
                rebase(_position.value)
                return
            }
            if (video.player.isPlaying) {
                val derived = activeClip.startUs + (video.player.currentPosition * 1000 - activeClip.sourceInUs)
                if (abs(derived - pos) > 40_000 && derived >= activeClip.startUs) {
                    rebase(derived)
                    pos = derived
                }
            }
        }
        if (pos >= p.durationUs) {
            _position.value = p.durationUs
            pause()
            return
        }
        _position.value = pos
        sync(pos, playing = true)
    }

    // ───────────────────────── slaving ─────────────────────────

    private fun sync(pos: Long, playing: Boolean, forceSeek: Boolean = false) {
        val p = project ?: return
        syncVisual(p, pos, playing, forceSeek)
        syncAudio(p, pos, playing, forceSeek)
    }

    private fun syncVisual(p: Project, pos: Long, playing: Boolean, forceSeek: Boolean) {
        val top = TimelineOps.topVisualClipAt(p, pos)
        val asset = top?.second?.let { p.asset(it.assetId) }
        if (top == null || asset == null) {
            _visual.value = Visual.None
            video.clipId = null
            video.player.playWhenReady = false
            return
        }
        val (track, clip) = top
        when (asset.type) {
            MediaType.IMAGE -> {
                video.clipId = null
                video.player.playWhenReady = false
                val cur = _visual.value
                if (cur !is Visual.Image || cur.asset.id != asset.id) _visual.value = Visual.Image(asset)
            }
            MediaType.VIDEO -> {
                drive(video, track, clip, asset, pos, playing, forceSeek, toleranceUs = 80_000)
                val cur = _visual.value
                if (cur !is Visual.Video || cur.clipId != clip.id) _visual.value = Visual.Video(clip.id, asset.id)
            }
            MediaType.AUDIO -> Unit
        }
    }

    private fun syncAudio(p: Project, pos: Long, playing: Boolean, forceSeek: Boolean) {
        val audioTracks = p.tracks.filter { it.kind == TrackKind.AUDIO }
        // Release players of tracks that no longer exist.
        val ids = audioTracks.map { it.id }.toSet()
        audio.keys.filter { it !in ids }.forEach { id -> audio.remove(id)?.player?.release() }
        for (t in audioTracks) {
            val clip = if (t.hidden) null else t.clipAt(pos)
            val asset = clip?.let { p.asset(it.assetId) }
            if (clip == null || asset == null) {
                audio[t.id]?.let { s -> s.player.playWhenReady = false; s.clipId = null }
                continue
            }
            val slave = audio.getOrPut(t.id) { Slave(newPlayer()) }
            drive(slave, t, clip, asset, pos, playing, forceSeek, toleranceUs = 120_000)
        }
    }

    private fun drive(
        s: Slave, track: Track, clip: Clip, asset: MediaAsset,
        pos: Long, playing: Boolean, forceSeek: Boolean, toleranceUs: Long,
    ) {
        val srcUs = clip.sourceTimeAt(pos).coerceIn(0, maxOf(0, asset.durationUs - 1))
        val targetMs = (srcUs + 999) / 1000 // ceil → lands on the frame that starts at srcUs
        if (s.uri != asset.uri) {
            s.player.setMediaItem(MediaItem.fromUri(asset.uri), targetMs)
            s.player.prepare()
            s.uri = asset.uri
        } else {
            val curMs = s.player.currentPosition
            val needSeek = when {
                forceSeek -> true
                !playing -> curMs != targetMs
                // Continuing playback (same clip or a contiguous cut of the same source).
                else -> s.clipId != clip.id && abs(curMs * 1000 - srcUs) > toleranceUs ||
                    abs(curMs * 1000 - srcUs) > toleranceUs * 4
            }
            if (needSeek) s.player.seekTo(targetMs)
        }
        s.clipId = clip.id
        s.player.volume = if (track.muted) 0f else clip.volume.coerceIn(0f, 1f)
        if (s.player.playWhenReady != playing) s.player.playWhenReady = playing
    }
}
