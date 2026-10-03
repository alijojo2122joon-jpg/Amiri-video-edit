package com.amiri.cut.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The project file is the single source of truth and is fully non-destructive:
 * it only stores references to source media (content URIs) and edit decisions.
 * All times are integer microseconds (µs) on the project timeline.
 */

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

@Serializable
enum class MediaType { VIDEO, IMAGE, AUDIO }

@Serializable
enum class TrackKind { VIDEO, TEXT, OVERLAY, AUDIO }

@Serializable
enum class CanvasBackground { BLACK, TRANSPARENT }

@Serializable
data class ProjectSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val aspectLabel: String,
    val resolutionLabel: String,
    val background: CanvasBackground = CanvasBackground.BLACK,
) {
    val aspect: Float get() = width.toFloat() / height.toFloat()
}

@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val type: MediaType,
    val name: String,
    /** Source duration. 0 for still images (unbounded). */
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    /** Display rotation from container metadata (0/90/180/270). */
    val rotation: Int = 0,
    val hasAudio: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
    /** Low-resolution proxy file (file:// URI) for faster preview, if generated. */
    val proxyUri: String? = null,
) {
    val isStill: Boolean get() = type == MediaType.IMAGE
    /** Width/height as displayed (rotation applied). */
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
}

/** One rotoscope mask keyframe: a mask image (in the clip's source frame) at a source time. */
@Serializable
data class RotoKey(
    val sourceUs: Long,
    /** File name inside the project's roto folder (immutable once written). */
    val file: String,
)

/**
 * Rotoscoping for a clip. The mask keeps what was painted and removes everything else.
 * At a given source time the latest key at or before it is used (or the first key).
 */
@Serializable
data class Roto(
    val enabled: Boolean = true,
    val invert: Boolean = false,
    val keys: List<RotoKey> = emptyList(),
) {
    fun keyAt(sourceUs: Long): RotoKey? =
        keys.lastOrNull { it.sourceUs <= sourceUs } ?: keys.firstOrNull()

    fun withKey(k: RotoKey): Roto =
        copy(keys = (keys.filterNot { it.sourceUs == k.sourceUs } + k).sortedBy { it.sourceUs })
}

@Serializable
enum class BlendMode(val label: String) {
    NORMAL("Normal"), SCREEN("Screen"), ADD("Add"), MULTIPLY("Multiply"), OVERLAY("Overlay"),
    SOFT_LIGHT("Soft Light"), HARD_LIGHT("Hard Light"), DARKEN("Darken"), LIGHTEN("Lighten"),
}

/** One effect in a clip's (or adjustment layer's) effect stack. Type ids are in EffectCatalog. */
@Serializable
data class Effect(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val props: Props = Props(),
    /** Non-animatable options (LUT file, preset name, blend mode…). */
    val opts: Map<String, String> = emptyMap(),
)

@Serializable
enum class MaskShape { RECT, ELLIPSE, PATH }

@Serializable
enum class MaskMode { ADD, SUBTRACT, INTERSECT }

/**
 * Shape mask in the clip's source frame (normalised 0..1). Props: x, y (center),
 * w, h (size), rot (deg), feather (0..0.5), expand (-0.5..0.5), opacity (0..1).
 * PATH masks use [path] (x0,y0,x1,y1,… normalised) as a closed polygon.
 */
@Serializable
data class ShapeMask(
    val id: String,
    val shape: MaskShape,
    val mode: MaskMode = MaskMode.ADD,
    val invert: Boolean = false,
    val props: Props = Props(),
    val path: List<Float> = emptyList(),
    /** Follow this clip's own motion-tracking data. */
    val followTrack: Boolean = false,
)

/** Text layer content and style. Animatable values live in [props] (see TextDefaults). */
@Serializable
data class TextSpec(
    val text: String = "Text",
    /** Font id: "sys:<family>" for system fonts or "file:<name>" for imported fonts. */
    val font: String = "sys:sans-serif",
    val align: Int = 1, // 0 start, 1 center, 2 end
    val bold: Boolean = false,
    val italic: Boolean = false,
    val props: Props = Props(),
)

@Serializable
enum class ShapeKind(val label: String) { RECT("Rectangle"), ELLIPSE("Ellipse"), POLYGON("Polygon"), STAR("Star"), LINE("Line") }

/** Vector shape layer. Animatable values in [props] (see ShapeSpecDefaults). */
@Serializable
data class ShapeSpec(
    val kind: ShapeKind = ShapeKind.RECT,
    val props: Props = Props(),
)

/** Speed ramp: relative speed shape across the clip (duration is preserved). */
@Serializable
data class SpeedRamp(
    val preset: String = "Custom",
    /** Relative speeds at equally spaced points from clip start to end (>= 2 values). */
    val points: List<Float> = listOf(1f, 1f, 1f, 1f, 1f),
)

/** A motion-tracking sample in the clip's source frame. */
@Serializable
data class TrackSample(
    val sourceUs: Long,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rot: Float = 0f,
)

@Serializable
data class TrackData(
    val samples: List<TrackSample> = emptyList(),
    val scaleRot: Boolean = false,
) {
    fun at(sourceUs: Long): TrackSample? {
        if (samples.isEmpty()) return null
        if (sourceUs <= samples.first().sourceUs) return samples.first()
        if (sourceUs >= samples.last().sourceUs) return samples.last()
        var lo = 0
        var hi = samples.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (samples[m].sourceUs <= sourceUs) lo = m else hi = m
        }
        val a = samples[lo]
        val b = samples[hi]
        val u = ((sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs).coerceAtLeast(1)).coerceIn(0f, 1f)
        return TrackSample(sourceUs, a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.scale + (b.scale - a.scale) * u, a.rot + (b.rot - a.rot) * u)
    }
}

/** Attach a layer's transform to another clip's tracking data. */
@Serializable
data class Follow(
    val clipId: String,
    /** Timeline time at which the attachment was made (offset reference). */
    val refTimelineUs: Long,
    val position: Boolean = true,
    val scale: Boolean = true,
    val rotation: Boolean = true,
)

@Serializable
enum class StabMode { BASIC, ADVANCED }

/** Per-frame stabilization correction (source time → canvas-relative offset). */
@Serializable
data class StabSample(val sourceUs: Long, val dx: Float, val dy: Float, val rot: Float = 0f, val scale: Float = 1f)

@Serializable
data class Stabilization(
    val mode: StabMode = StabMode.BASIC,
    val smoothness: Float = 0.5f,
    val zoom: Float = 1.08f,
    val enabled: Boolean = true,
    val samples: List<StabSample> = emptyList(),
) {
    fun at(sourceUs: Long): StabSample? {
        if (samples.isEmpty()) return null
        val i = samples.binarySearchBy(sourceUs) { it.sourceUs }.let { if (it < 0) (-it - 2).coerceAtLeast(0) else it }
        return samples[i.coerceIn(0, samples.size - 1)]
    }
}

enum class ClipKind { MEDIA, TEXT, SHAPE, ADJUSTMENT }

@Serializable
data class Clip(
    val id: String,
    /** Media asset id; "" for text and adjustment clips. */
    val assetId: String,
    val name: String,
    /** Position of the clip's first frame on the timeline. */
    val startUs: Long,
    /** In/out points inside the source media. For stills/text/adjustment: 0..displayDuration. */
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val locked: Boolean = false,
    /** Constant speed multiplier (duration = source length / speed). */
    val speed: Float = 1f,
    /** Legacy constant volume (Stage 1). Superseded by [audio] "volume". */
    val volume: Float = 1f,
    /** Rotoscope mask (null = none). */
    val roto: Roto? = null,
    // ── Stage 2+ ──
    val transform: Props = Props(),
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val blend: BlendMode = BlendMode.NORMAL,
    val effects: List<Effect> = emptyList(),
    val masks: List<ShapeMask> = emptyList(),
    val text: TextSpec? = null,
    val shape: ShapeSpec? = null,
    val adjustment: Boolean = false,
    /** Audio: volume (0..2), fadeIn / fadeOut (seconds). */
    val audio: Props = Props(),
    val muted: Boolean = false,
    val ramp: SpeedRamp? = null,
    /** When reversed, the clip plays a reversed proxy; this keeps the original asset id. */
    val reversedFrom: String? = null,
    val tracking: TrackData? = null,
    val follow: Follow? = null,
    val stab: Stabilization? = null,
) {
    val kind: ClipKind get() = when {
        text != null -> ClipKind.TEXT
        shape != null -> ClipKind.SHAPE
        adjustment -> ClipKind.ADJUSTMENT
        else -> ClipKind.MEDIA
    }

    val durationUs: Long get() = sourceToTimeline(sourceOutUs - sourceInUs)
    val endUs: Long get() = startUs + durationUs

    /** Source duration → timeline duration (exact integer math at 1×; Double otherwise). */
    fun sourceToTimeline(d: Long): Long = if (speed == 1f) d else (d / speed.toDouble()).toLong()

    /** Timeline duration → source duration. */
    fun timelineToSource(d: Long): Long = if (speed == 1f) d else (d * speed.toDouble()).toLong()

    /** Maps a timeline time inside this clip to a source-media time (speed ramp aware). */
    fun sourceTimeAt(timelineUs: Long): Long {
        val r = ramp
        if (r == null || r.points.size < 2) return sourceInUs + timelineToSource(timelineUs - startUs)
        val d = durationUs.coerceAtLeast(1)
        val u = ((timelineUs - startUs).toDouble() / d).coerceIn(0.0, 1.0)
        return sourceInUs + ((sourceOutUs - sourceInUs) * SpeedMath.cumulative(r.points, u)).toLong()
    }

    /** Instantaneous playback speed at a timeline time (for the preview players). */
    fun speedAt(timelineUs: Long): Float {
        val r = ramp ?: return speed
        val d = durationUs.coerceAtLeast(1)
        val u = ((timelineUs - startUs).toDouble() / d).coerceIn(0.0, 1.0)
        return (speed * SpeedMath.relative(r.points, u)).toFloat()
    }

    fun contains(timelineUs: Long): Boolean = timelineUs >= startUs && timelineUs < endUs

    /** Clip-local time used for keyframes. */
    fun local(timelineUs: Long): Long = timelineUs - startUs

    /** Shifts every keyframe time by [dt] (used when the clip's start is trimmed/split). */
    fun shiftKeys(dt: Long): Clip = if (dt == 0L) this else copy(
        transform = transform.shift(dt),
        effects = effects.map { it.copy(props = it.props.shift(dt)) },
        masks = masks.map { it.copy(props = it.props.shift(dt)) },
        text = text?.let { it.copy(props = it.props.shift(dt)) },
        shape = shape?.let { it.copy(props = it.props.shift(dt)) },
        audio = audio.shift(dt),
    )

    /** All keyframe times (clip-local) for timeline display. */
    fun keyTimes(): List<Long> = (transform.keyTimes() + effects.flatMap { it.props.keyTimes() } +
        masks.flatMap { it.props.keyTimes() } + (text?.props?.keyTimes() ?: emptyList()) +
        (shape?.props?.keyTimes() ?: emptyList()) + audio.keyTimes()).distinct().sorted()
}

/** Speed ramp math: normalised so the average relative speed is 1 (duration preserved). */
object SpeedMath {
    private fun raw(points: List<Float>, u: Double): Double {
        val n = points.size - 1
        val x = (u * n).coerceIn(0.0, n.toDouble())
        val i = x.toInt().coerceAtMost(n - 1)
        val f = x - i
        return (points[i] + (points[i + 1] - points[i]) * f).coerceAtLeast(0.05)
    }

    private fun mean(points: List<Float>): Double {
        var s = 0.0
        val steps = 64
        for (k in 0 until steps) s += raw(points, (k + 0.5) / steps)
        return s / steps
    }

    fun relative(points: List<Float>, u: Double): Double = raw(points, u) / mean(points)

    /** ∫₀ᵘ relative speed, normalised to 1 at u = 1. */
    fun cumulative(points: List<Float>, u: Double): Double {
        val steps = 64
        var total = 0.0
        var acc = 0.0
        val target = u.coerceIn(0.0, 1.0)
        for (k in 0 until steps) {
            val a = k.toDouble() / steps
            val b = (k + 1).toDouble() / steps
            val v = raw(points, (a + b) / 2) / steps
            total += v
            if (b <= target) acc += v
            else if (a < target) acc += v * (target - a) * steps
        }
        return if (total <= 0) u else acc / total
    }
}

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    /** Always kept sorted by [Clip.startUs] and non-overlapping. */
    val clips: List<Clip> = emptyList(),
) {
    fun clipAt(timelineUs: Long): Clip? = clips.firstOrNull { it.contains(timelineUs) }
    val acceptsVisual: Boolean get() = kind == TrackKind.VIDEO || kind == TrackKind.OVERLAY || kind == TrackKind.TEXT
    val acceptsAudio: Boolean get() = kind == TrackKind.AUDIO
}

@Serializable
data class Marker(
    val id: String,
    val timeUs: Long,
    val label: String = "",
)

@Serializable
data class Project(
    val formatVersion: Int = 1,
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val settings: ProjectSettings,
    val assets: List<MediaAsset> = emptyList(),
    /**
     * Tracks in DISPLAY order (top → bottom). For compositing, a visual track that
     * appears earlier in this list is drawn above the ones after it.
     */
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val playheadUs: Long = 0,
) {
    val durationUs: Long get() = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endUs } ?: 0L } ?: 0L

    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    fun trackOfClip(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }
    fun clip(clipId: String): Clip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == clipId } }

    companion object {
        fun defaultTracks(): List<Track> = listOf(
            Track(newId(), TrackKind.TEXT, "Text"),
            Track(newId(), TrackKind.OVERLAY, "Overlay"),
            Track(newId(), TrackKind.VIDEO, "V4"),
            Track(newId(), TrackKind.VIDEO, "V3"),
            Track(newId(), TrackKind.VIDEO, "V2"),
            Track(newId(), TrackKind.VIDEO, "V1"),
            Track(newId(), TrackKind.AUDIO, "A1"),
            Track(newId(), TrackKind.AUDIO, "A2"),
            Track(newId(), TrackKind.AUDIO, "A3"),
        )
    }
}

/** Lightweight info for the Home screen list. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val aspectLabel: String,
    val modifiedAt: Long,
    val durationUs: Long,
    val thumbPath: String?,
    val hasRecovery: Boolean,
)
