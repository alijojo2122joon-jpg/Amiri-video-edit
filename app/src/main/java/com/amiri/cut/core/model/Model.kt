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
data class Clip(
    val id: String,
    val assetId: String,
    val name: String,
    /** Position of the clip's first frame on the timeline. */
    val startUs: Long,
    /** In/out points inside the source media. For stills: 0..displayDuration. */
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val locked: Boolean = false,
    /** Reserved for Stage 2/10 (speed tools). Always 1.0 in Stage 1. */
    val speed: Float = 1f,
    /** Reserved for Stage 10 (audio tools). */
    val volume: Float = 1f,
    /** Rotoscope mask (null = none). */
    val roto: Roto? = null,
) {
    val durationUs: Long get() = sourceToTimeline(sourceOutUs - sourceInUs)
    val endUs: Long get() = startUs + durationUs

    /** Source duration → timeline duration (exact integer math at 1×; Double otherwise). */
    fun sourceToTimeline(d: Long): Long = if (speed == 1f) d else (d / speed.toDouble()).toLong()

    /** Timeline duration → source duration. */
    fun timelineToSource(d: Long): Long = if (speed == 1f) d else (d * speed.toDouble()).toLong()

    /** Maps a timeline time inside this clip to a source-media time. */
    fun sourceTimeAt(timelineUs: Long): Long = sourceInUs + timelineToSource(timelineUs - startUs)

    fun contains(timelineUs: Long): Boolean = timelineUs >= startUs && timelineUs < endUs
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
    val acceptsVisual: Boolean get() = kind == TrackKind.VIDEO || kind == TrackKind.OVERLAY
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
