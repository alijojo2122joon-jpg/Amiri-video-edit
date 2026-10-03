package com.amiri.cut.engine

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.amiri.cut.core.effects.AudioSpec
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.render.RenderOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** Snapshot the GL renderer draws: which project, which time, which player slot shows which clip. */
class FrameState(
    val project: Project,
    val timeUs: Long,
    val slotOfClip: Map<String, Int>,
    val options: RenderOptions,
)

/**
 * Preview engine. The timeline owns the clock; hardware-decoding ExoPlayer instances are
 * slaved to it. Up to [SLOTS] visible video layers decode at once, each into its own
 * SurfaceTexture owned by the GL renderer, which composites every layer (videos, photos,
 * text, effects, adjustment layers) with the same Compositor used for export.
 *
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class PreviewEngine(private val context: Context) {

    private class Slave(val player: ExoPlayer) {
        var uri: String? = null
        var clipId: String? = null
        var speed: Float = 1f
    }

    private val scope = MainScope()
    private val slots = List(SLOTS) { Slave(newPlayer()) }
    private val audio = HashMap<String, Slave>()

    private var project: Project? = null

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** Latest state for the renderer (read from the GL thread). */
    @Volatile var frameState: FrameState? = null
        private set

    /** Called whenever a new frame should be drawn (renderer.requestRender). */
    @Volatile var onInvalidate: (() -> Unit)? = null

    /** Render options set by the editor (before/after, roto painting). */
    var options: RenderOptions = RenderOptions(checker = true)
        set(v) {
            field = v
            publish(_position.value)
        }

    /** Use proxy files for decoding when available. */
    var useProxies: Boolean = false

    private var clockJob: Job? = null
    private var baseUs = 0L
    private var baseNanos = 0L
    private var pendingScrub = false
    private var lastAssign: Map<String, Int> = emptyMap()
    private var primarySlot = -1

    init {
        slots.forEach { s ->
            s.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && pendingScrub && !_playing.value) {
                        pendingScrub = false
                        sync(_position.value, playing = false)
                    }
                }
            })
        }
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setSeekParameters(SeekParameters.EXACT)
        }

    // ───────────────────────── surfaces (from the GL renderer) ─────────────────────────

    fun attachSurfaces(list: List<Surface>) {
        slots.forEachIndexed { i, s -> list.getOrNull(i)?.let { s.player.setVideoSurface(it) } }
        // Decode the visible frames again into the new surfaces.
        slots.forEach { it.uri = null; it.clipId = null }
        sync(_position.value, _playing.value, forceSeek = true)
    }

    fun detachSurfaces() {
        slots.forEach { it.player.clearVideoSurface() }
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
        sync(_position.value, playing = true, forceSeek = true)
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
        val q = if (p != null) FrameTime.quantize(_position.value, p.settings.fps) else _position.value
        _position.value = q
        sync(q, playing = false)
    }

    fun seekTo(us: Long) {
        val p = project ?: return
        val q = FrameTime.quantize(us.coerceIn(0, p.durationUs), p.settings.fps)
        _position.value = q
        if (_playing.value) {
            rebase(q)
            sync(q, playing = true, forceSeek = true)
            return
        }
        if (slots.any { it.clipId != null && it.player.playbackState == Player.STATE_BUFFERING }) {
            pendingScrub = true
            publish(q)
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

    /** Redraws the current frame (after a project/option change while paused). */
    fun refreshFrame() = publish(_position.value)

    fun release() {
        clockJob?.cancel()
        scope.cancel()
        slots.forEach { it.player.release() }
        audio.values.forEach { it.player.release() }
        audio.clear()
        onInvalidate = null
    }

    // ───────────────────────── clock ─────────────────────────

    private fun rebase(us: Long) {
        baseUs = us
        baseNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun tick() {
        val p = project ?: return
        var pos = baseUs + (SystemClock.elapsedRealtimeNanos() - baseNanos) / 1000
        // Hold the clock while a visible layer's decoder is still buffering.
        if (lastAssign.values.any { slots[it].player.playbackState == Player.STATE_BUFFERING }) {
            rebase(_position.value)
            return
        }
        val ps = primarySlot
        if (ps >= 0) {
            val s = slots[ps]
            val clip = s.clipId?.let { p.clip(it) }
            if (clip != null && clip.ramp == null && s.player.isPlaying) {
                val derived = clip.startUs + clip.sourceToTimeline(s.player.currentPosition * 1000 - clip.sourceInUs)
                if (abs(derived - pos) > 40_000 && derived >= clip.startUs) {
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

    private fun publish(pos: Long) {
        val p = project ?: return
        frameState = FrameState(p, pos, lastAssign, options)
        onInvalidate?.invoke()
    }

    private fun sync(pos: Long, playing: Boolean, forceSeek: Boolean = false) {
        val p = project ?: return
        syncVideo(p, pos, playing, forceSeek)
        syncAudio(p, pos, playing, forceSeek)
        publish(pos)
    }

    private fun uriOf(a: MediaAsset): String = a.proxyUri?.takeIf { useProxies } ?: a.uri

    private fun syncVideo(p: Project, pos: Long, playing: Boolean, forceSeek: Boolean) {
        val vids = ArrayList<Triple<Track, Clip, MediaAsset>>()
        for (t in p.tracks) {
            if (!t.acceptsVisual || t.hidden) continue
            val c = t.clipAt(pos) ?: continue
            if (c.kind != ClipKind.MEDIA) continue
            val a = p.asset(c.assetId) ?: continue
            if (a.type != MediaType.VIDEO) continue
            vids += Triple(t, c, a)
            if (vids.size == SLOTS) break
        }
        val assign = HashMap<String, Int>()
        val used = BooleanArray(SLOTS)
        for ((_, c, _) in vids) {
            val i = slots.indexOfFirst { it.clipId == c.id }
            if (i >= 0 && !used[i]) { assign[c.id] = i; used[i] = true }
        }
        for ((_, c, a) in vids) {
            if (c.id in assign) continue
            var i = (0 until SLOTS).firstOrNull { !used[it] && slots[it].uri == uriOf(a) } ?: -1
            if (i < 0) i = (0 until SLOTS).firstOrNull { !used[it] && slots[it].clipId == null } ?: -1
            if (i < 0) i = (0 until SLOTS).firstOrNull { !used[it] } ?: -1
            if (i >= 0) { assign[c.id] = i; used[i] = true }
        }
        for (i in 0 until SLOTS) if (!used[i]) {
            slots[i].clipId = null
            if (slots[i].player.playWhenReady) slots[i].player.playWhenReady = false
        }
        for ((t, c, a) in vids) {
            val i = assign[c.id] ?: continue
            drive(slots[i], t, c, a, pos, playing, forceSeek, toleranceUs = 80_000)
        }
        primarySlot = vids.lastOrNull()?.let { assign[it.second.id] } ?: -1
        lastAssign = assign
    }

    private fun syncAudio(p: Project, pos: Long, playing: Boolean, forceSeek: Boolean) {
        val audioTracks = p.tracks.filter { it.kind == TrackKind.AUDIO }
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
        val uri = uriOf(asset)
        val srcUs = clip.sourceTimeAt(pos).coerceIn(0, maxOf(0, asset.durationUs - 1))
        val targetMs = (srcUs + 999) / 1000
        if (s.uri != uri) {
            s.player.setMediaItem(MediaItem.fromUri(uri), targetMs)
            s.player.prepare()
            s.uri = uri
        } else {
            val curMs = s.player.currentPosition
            val needSeek = when {
                forceSeek -> true
                !playing -> curMs != targetMs
                else -> s.clipId != clip.id && abs(curMs * 1000 - srcUs) > toleranceUs ||
                    abs(curMs * 1000 - srcUs) > toleranceUs * 4
            }
            if (needSeek) s.player.seekTo(targetMs)
        }
        s.clipId = clip.id
        s.player.volume = gainAt(track, clip, pos).coerceIn(0f, 1f)
        val sp = clip.speedAt(pos).coerceIn(0.1f, 8f)
        if (abs(sp - s.speed) > 0.01f) {
            s.player.playbackParameters = PlaybackParameters(sp)
            s.speed = sp
        }
        if (s.player.playWhenReady != playing) s.player.playWhenReady = playing
    }

    companion object {
        const val SLOTS = 4

        /** Clip gain at [pos]: volume keyframes × fade envelope × mute (0..2). */
        fun gainAt(track: Track, clip: Clip, pos: Long): Float {
            if (track.muted || clip.muted) return 0f
            val local = pos - clip.startUs
            val vol = clip.audio.at("volume", local, AudioSpec.def("volume")) * clip.volume
            val fi = clip.audio.at("fadeIn", local, 0f) * 1_000_000f
            val fo = clip.audio.at("fadeOut", local, 0f) * 1_000_000f
            var g = vol
            if (fi > 1f) g *= min(1f, local / fi)
            if (fo > 1f) g *= min(1f, (clip.durationUs - local) / fo)
            return g.coerceIn(0f, 2f)
        }
    }
}
