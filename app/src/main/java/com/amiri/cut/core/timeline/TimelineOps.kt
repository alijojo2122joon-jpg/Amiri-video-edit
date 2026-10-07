package com.amiri.cut.core.timeline

import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.TextSpec
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Marker
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.model.newId
import com.amiri.cut.core.time.FrameTime

/**
 * Pure, side-effect-free timeline editing operations. Every function takes a
 * [Project] and returns a new one (or null when the edit is not allowed), which
 * makes Undo/Redo a simple snapshot stack and keeps the logic unit-testable.
 *
 * Invariants maintained here:
 *  - clips on a track never overlap and are sorted by start
 *  - every clip is at least one frame long
 *  - clip source ranges stay inside the source media (stills are unbounded)
 *  - locked tracks and locked clips are never modified
 */
object TimelineOps {

    const val DEFAULT_STILL_DURATION_US = 3_000_000L

    // ───────────────────────── helpers ─────────────────────────

    fun Project.mapTrack(trackId: String, f: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.id == trackId) f(it) else it })

    private fun Track.withClips(list: List<Clip>): Track = copy(clips = list.sortedBy { it.startUs })

    fun isFree(track: Track, startUs: Long, endUs: Long, ignoreClipId: String? = null): Boolean =
        track.clips.none { it.id != ignoreClipId && it.startUs < endUs && startUs < it.endUs }

    /** Can [clip] live on [track]? Text → Text/Overlay; adjustment → any visual track; media by type. */
    fun compatibleClip(track: Track, clip: Clip, asset: MediaAsset?): Boolean = when (clip.kind) {
        ClipKind.TEXT, ClipKind.SHAPE -> track.kind != TrackKind.AUDIO
        ClipKind.ADJUSTMENT -> track.kind != TrackKind.AUDIO
        ClipKind.MEDIA -> asset != null && compatible(track, asset)
    }

    fun compatible(track: Track, asset: MediaAsset): Boolean = when (asset.type) {
        MediaType.AUDIO -> track.kind == TrackKind.AUDIO
        MediaType.VIDEO -> track.kind == TrackKind.VIDEO || track.kind == TrackKind.OVERLAY ||
            (track.kind == TrackKind.AUDIO && asset.hasAudio)
        MediaType.IMAGE -> track.kind == TrackKind.VIDEO || track.kind == TrackKind.OVERLAY
    }

    private fun frame(fps: Int): Long = FrameTime.fromFrame(1, fps).coerceAtLeast(1)

    // ───────────────────────── assets & placement ─────────────────────────

    fun addAsset(p: Project, asset: MediaAsset): Project =
        if (p.assets.any { it.id == asset.id }) p else p.copy(assets = p.assets + asset)

    fun newClipFor(asset: MediaAsset, startUs: Long): Clip {
        val out = if (asset.isStill) DEFAULT_STILL_DURATION_US else asset.durationUs
        return Clip(
            id = newId(),
            assetId = asset.id,
            name = asset.name,
            startUs = startUs,
            sourceInUs = 0,
            sourceOutUs = out,
        )
    }

    /** Video tracks from V1 upwards (bottom of the visual stack first). */
    private fun videoTracksBottomUp(p: Project): List<Track> =
        p.tracks.filter { it.kind == TrackKind.VIDEO }.reversed()

    /**
     * Places an asset at [atUs]. Visual media goes to the lowest free, unlocked video
     * track; audio to the first free audio track. A new track is created when every
     * track is busy at that time range.
     */
    fun placeAsset(p: Project, asset: MediaAsset, atUs: Long): Pair<Project, Clip> {
        val fps = p.settings.fps
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), fps)
        val clip = newClipFor(asset, start)
        val candidates = if (asset.type == MediaType.AUDIO) {
            p.tracks.filter { it.kind == TrackKind.AUDIO }
        } else {
            videoTracksBottomUp(p)
        }
        val target = candidates.firstOrNull { !it.locked && isFree(it, clip.startUs, clip.endUs) }
        var proj = addAsset(p, asset)
        val trackId = if (target != null) {
            target.id
        } else {
            val kind = if (asset.type == MediaType.AUDIO) TrackKind.AUDIO else TrackKind.VIDEO
            proj = addTrack(proj, kind)
            if (kind == TrackKind.AUDIO) proj.tracks.last { it.kind == TrackKind.AUDIO }.id
            else proj.tracks.first { it.kind == TrackKind.VIDEO }.id
        }
        proj = proj.mapTrack(trackId) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /** Appends an asset to the end of the main track (V1 for visuals, A1 for audio). */
    fun appendToMain(p: Project, asset: MediaAsset): Pair<Project, Clip> {
        val track = if (asset.type == MediaType.AUDIO) {
            p.tracks.firstOrNull { it.kind == TrackKind.AUDIO && !it.locked }
        } else {
            videoTracksBottomUp(p).firstOrNull { !it.locked }
        } ?: return placeAsset(p, asset, p.durationUs)
        val end = track.clips.maxOfOrNull { it.endUs } ?: 0L
        val clip = newClipFor(asset, end)
        val proj = addAsset(p, asset).mapTrack(track.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    // ───────────────────────── tracks ─────────────────────────

    fun addTrack(p: Project, kind: TrackKind): Project {
        return when (kind) {
            TrackKind.VIDEO -> {
                val n = p.tracks.count { it.kind == TrackKind.VIDEO } + 1
                val t = Track(newId(), TrackKind.VIDEO, "V$n")
                val firstVideo = p.tracks.indexOfFirst { it.kind == TrackKind.VIDEO }
                val idx = if (firstVideo >= 0) firstVideo else p.tracks.indexOfFirst { it.kind == TrackKind.AUDIO }.let { if (it < 0) p.tracks.size else it }
                p.copy(tracks = p.tracks.toMutableList().apply { add(idx, t) })
            }
            TrackKind.AUDIO -> {
                val n = p.tracks.count { it.kind == TrackKind.AUDIO } + 1
                p.copy(tracks = p.tracks + Track(newId(), TrackKind.AUDIO, "A$n"))
            }
            TrackKind.OVERLAY, TrackKind.TEXT -> {
                val n = p.tracks.count { it.kind == kind } + 1
                val base = if (kind == TrackKind.TEXT) "Text" else "Overlay"
                val t = Track(newId(), kind, "$base $n")
                val idx = p.tracks.indexOfLast { it.kind == kind }.let { if (it < 0) 0 else it + 1 }
                p.copy(tracks = p.tracks.toMutableList().apply { add(idx, t) })
            }
        }
    }

    fun setTrackLocked(p: Project, trackId: String, locked: Boolean) = p.mapTrack(trackId) { it.copy(locked = locked) }
    fun setTrackHidden(p: Project, trackId: String, hidden: Boolean) = p.mapTrack(trackId) { it.copy(hidden = hidden) }
    fun setTrackMuted(p: Project, trackId: String, muted: Boolean) = p.mapTrack(trackId) { it.copy(muted = muted) }

    /**
     * Removes a track together with its clips (undoable). Locked tracks stay, and the last
     * main video track is kept so there is always somewhere to put footage.
     */
    fun removeTrack(p: Project, trackId: String): Project? {
        val t = p.track(trackId) ?: return null
        if (t.locked) return null
        if (t.kind == TrackKind.VIDEO && p.tracks.count { it.kind == TrackKind.VIDEO } <= 1) return null
        return p.copy(tracks = p.tracks.filterNot { it.id == trackId })
    }

    // ───────────────────────── clip edits ─────────────────────────

    private fun editable(p: Project, clipId: String): Pair<Track, Clip>? {
        val track = p.trackOfClip(clipId) ?: return null
        val clip = track.clips.first { it.id == clipId }
        if (track.locked || clip.locked) return null
        return track to clip
    }

    /** Splits a clip at [atUs] (quantized). Each side must keep at least one frame. */
    fun split(p: Project, clipId: String, atUs: Long): Pair<Project, Clip>? {
        val (track, clip) = editable(p, clipId) ?: return null
        val fps = p.settings.fps
        val at = FrameTime.quantize(atUs, fps)
        val f = frame(fps)
        if (at - clip.startUs < f || clip.endUs - at < f) return null
        val sourceAt = clip.sourceTimeAt(at)
        // The entrance animation stays on the left part, the exit moves to the right part.
        val left = clip.copy(sourceOutUs = sourceAt, anim = clip.anim?.copy(outId = "None"))
        val right = clip.copy(id = newId(), startUs = at, sourceInUs = sourceAt, transIn = null, anim = clip.anim?.copy(inId = "None")).shiftKeys(-(at - clip.startUs))
        val proj = p.mapTrack(track.id) { t -> t.withClips(t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) }) }
        return proj to right
    }

    /** Splits every unlocked clip under the playhead on all unlocked tracks. */
    fun splitAll(p: Project, atUs: Long): Project? {
        var proj = p
        var any = false
        for (t in p.tracks) {
            if (t.locked) continue
            val c = t.clipAt(atUs) ?: continue
            val r = split(proj, c.id, atUs) ?: continue
            proj = r.first
            any = true
        }
        return if (any) proj else null
    }

    fun delete(p: Project, clipId: String): Project? {
        val (track, _) = editable(p, clipId) ?: return null
        return p.mapTrack(track.id) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }
    }

    /** Deletes a clip and closes the gap by shifting later clips on the same track. */
    fun rippleDelete(p: Project, clipId: String): Project? {
        val (track, clip) = editable(p, clipId) ?: return null
        val d = clip.durationUs
        if (track.clips.any { it.startUs >= clip.endUs && it.locked }) return null
        return p.mapTrack(track.id) { t ->
            t.withClips(t.clips.filterNot { it.id == clipId }.map {
                if (it.startUs >= clip.endUs) it.copy(startUs = it.startUs - d) else it
            })
        }
    }

    /** Duplicates a clip to the first free spot right after it on the same track. */
    fun duplicate(p: Project, clipId: String): Pair<Project, Clip>? {
        val (track, clip) = editable(p, clipId) ?: return null
        val d = clip.durationUs
        var start = clip.endUs
        for (c in track.clips.filter { it.endUs > clip.endUs }) {
            if (c.startUs - start >= d) break
            start = maxOf(start, c.endUs)
        }
        val copy = clip.copy(id = newId(), startUs = start, locked = false)
        val proj = p.mapTrack(track.id) { it.withClips(it.clips + copy) }
        return proj to copy
    }

    fun setClipLocked(p: Project, clipId: String, locked: Boolean): Project? {
        val track = p.trackOfClip(clipId) ?: return null
        if (track.locked) return null
        return p.mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(locked = locked) else it }) }
    }

    /** Moves a clip to [targetTrackId] at [newStartUs]. Returns null if it would overlap. */
    fun move(p: Project, clipId: String, targetTrackId: String, newStartUs: Long): Project? {
        val (src, clip) = editable(p, clipId) ?: return null
        val target = p.track(targetTrackId) ?: return null
        if (target.locked) return null
        val asset = p.asset(clip.assetId)
        if (!compatibleClip(target, clip, asset)) return null
        val start = FrameTime.quantize(newStartUs, p.settings.fps)
        if (start < 0) return null
        val moved = clip.copy(startUs = start)
        if (!isFree(target, moved.startUs, moved.endUs, ignoreClipId = clip.id)) return null
        var proj = p.mapTrack(src.id) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }
        proj = proj.mapTrack(target.id) { t -> t.withClips(t.clips.filterNot { it.id == clipId } + moved) }
        return proj
    }

    /** Computes the clip that results from dragging its left edge to [newStartUs]. */
    fun trimmedStart(p: Project, clipId: String, newStartUs: Long): Clip? {
        val (track, clip) = editable(p, clipId) ?: return null
        val asset = p.asset(clip.assetId)
        val fps = p.settings.fps
        val f = frame(fps)
        val prevEnd = track.clips.filter { it.id != clip.id && it.endUs <= clip.startUs }.maxOfOrNull { it.endUs } ?: 0L
        var start = FrameTime.quantize(newStartUs, fps).coerceIn(prevEnd, clip.endUs - f)
        if (asset == null || asset.isStill) {
            return clip.copy(startUs = start, sourceInUs = 0, sourceOutUs = clip.endUs - start).shiftKeys(-(start - clip.startUs))
        }
        // Can't extend before the first source frame.
        val minStart = clip.startUs - clip.sourceToTimeline(clip.sourceInUs)
        start = start.coerceAtLeast(minStart)
        val newIn = clip.sourceInUs + clip.timelineToSource(start - clip.startUs)
        return clip.copy(startUs = start, sourceInUs = newIn.coerceAtLeast(0)).shiftKeys(-(start - clip.startUs))
    }

    /** Computes the clip that results from dragging its right edge to [newEndUs]. */
    fun trimmedEnd(p: Project, clipId: String, newEndUs: Long): Clip? {
        val (track, clip) = editable(p, clipId) ?: return null
        val asset = p.asset(clip.assetId)
        val fps = p.settings.fps
        val f = frame(fps)
        val nextStart = track.clips.filter { it.id != clip.id && it.startUs >= clip.endUs }.minOfOrNull { it.startUs } ?: Long.MAX_VALUE
        var end = FrameTime.quantize(newEndUs, fps).coerceIn(clip.startUs + f, nextStart)
        if (asset == null || asset.isStill) {
            return clip.copy(sourceInUs = 0, sourceOutUs = clip.timelineToSource(end - clip.startUs))
        }
        val maxEnd = clip.startUs + clip.sourceToTimeline(asset.durationUs - clip.sourceInUs)
        end = end.coerceAtMost(maxEnd)
        val newOut = clip.sourceInUs + clip.timelineToSource(end - clip.startUs)
        return clip.copy(sourceOutUs = newOut.coerceAtMost(asset.durationUs))
    }

    fun replaceClip(p: Project, updated: Clip): Project? {
        val track = p.trackOfClip(updated.id) ?: return null
        if (!isFree(track, updated.startUs, updated.endUs, ignoreClipId = updated.id)) return null
        if (updated.durationUs <= 0) return null
        return p.mapTrack(track.id) { t -> t.withClips(t.clips.map { if (it.id == updated.id) updated else it }) }
    }

    // ───────────────────────── media ─────────────────────────

    /**
     * Points an asset at a new source file (Replace Media / Relink Missing Media).
     * Clip in/out points are clamped to the new media's duration.
     */
    fun replaceAssetSource(p: Project, assetId: String, newAsset: MediaAsset): Project {
        val fixed = newAsset.copy(id = assetId)
        return p.copy(
            assets = p.assets.map { if (it.id == assetId) fixed else it },
            tracks = p.tracks.map { t ->
                t.copy(clips = t.clips.mapNotNull { c ->
                    if (c.assetId != assetId || fixed.isStill) c
                    else {
                        val outUs = c.sourceOutUs.coerceAtMost(fixed.durationUs)
                        val inUs = c.sourceInUs.coerceAtMost(outUs - 1)
                        if (outUs - inUs <= 0) null else c.copy(sourceInUs = inUs, sourceOutUs = outUs)
                    }
                })
            },
        )
    }

    /** Removes an asset from the bin; refuses while any clip uses it. */
    fun removeAsset(p: Project, assetId: String): Project? {
        if (p.tracks.any { t -> t.clips.any { it.assetId == assetId } }) return null
        return p.copy(assets = p.assets.filterNot { it.id == assetId })
    }

    // ───────────────────────── markers ─────────────────────────

    fun addMarker(p: Project, atUs: Long): Project {
        val t = FrameTime.quantize(atUs, p.settings.fps)
        if (p.markers.any { it.timeUs == t }) return p
        val label = "M${p.markers.size + 1}"
        return p.copy(markers = (p.markers + Marker(newId(), t, label)).sortedBy { it.timeUs })
    }

    fun removeMarker(p: Project, markerId: String): Project = p.copy(markers = p.markers.filterNot { it.id == markerId })

    /** Adds beat markers (label "♪") at timeline times [timesUs], replacing old beat markers in that range. */
    fun setBeatMarkers(p: Project, timesUs: List<Long>, fromUs: Long, toUs: Long): Project {
        val keep = p.markers.filterNot { it.label == "♪" && it.timeUs in fromUs..toUs }
        val fresh = timesUs.map { FrameTime.quantize(it, p.settings.fps) }.distinct()
            .filter { t -> keep.none { it.timeUs == t } }.map { Marker(newId(), it, "♪") }
        return p.copy(markers = (keep + fresh).sortedBy { it.timeUs })
    }

    /** Splits [clipId] at every marker strictly inside it. Returns null if nothing was cut. */
    fun splitAtMarkers(p: Project, clipId: String, beatsOnly: Boolean = true): Project? {
        var proj = p
        var cur = p.clip(clipId) ?: return null
        var any = false
        val f = frame(p.settings.fps)
        for (m in p.markers.sortedBy { it.timeUs }) {
            if (beatsOnly && m.label != "♪") continue
            if (m.timeUs - cur.startUs < f || cur.endUs - m.timeUs < f) continue
            val r = split(proj, cur.id, m.timeUs) ?: continue
            proj = r.first; cur = r.second; any = true
        }
        return if (any) proj else null
    }

    // ───────────────────────── navigation & snapping ─────────────────────────

    /** All clip boundaries + 0 + markers, sorted & distinct. */
    fun editPoints(p: Project): List<Long> {
        val pts = sortedSetOf(0L)
        p.tracks.forEach { t -> t.clips.forEach { pts += it.startUs; pts += it.endUs } }
        p.markers.forEach { pts += it.timeUs }
        return pts.toList()
    }

    fun snapTargets(p: Project, excludeClipId: String?, playheadUs: Long): List<Long> {
        val pts = sortedSetOf(0L, playheadUs)
        p.tracks.forEach { t -> t.clips.forEach { if (it.id != excludeClipId) { pts += it.startUs; pts += it.endUs } } }
        p.markers.forEach { pts += it.timeUs }
        return pts.toList()
    }

    /** Returns the snapped value and whether a snap happened. */
    fun snap(value: Long, targets: List<Long>, thresholdUs: Long): Pair<Long, Boolean> {
        var best = value
        var bestD = thresholdUs + 1
        for (t in targets) {
            val d = kotlin.math.abs(t - value)
            if (d < bestD) { bestD = d; best = t }
        }
        return if (bestD <= thresholdUs) best to true else value to false
    }

    /** Top-most visible visual clip at [us] (what the preview shows in Stage 1). */
    fun topVisualClipAt(p: Project, us: Long): Pair<Track, Clip>? {
        for (t in p.tracks) {
            if (!t.acceptsVisual || t.hidden) continue
            val c = t.clipAt(us) ?: continue
            if (c.kind != ClipKind.MEDIA) continue
            return t to c
        }
        return null
    }

    // ───────────────────────── Stage 2+ clip kinds & ops ─────────────────────────

    private fun freeVisualTrack(p: Project, kinds: List<TrackKind>, start: Long, end: Long): Track? =
        p.tracks.firstOrNull { it.kind in kinds && !it.locked && isFree(it, start, end) }

    /**
     * Puts a photo/video on a free overlay track at [atUs] (creating one if needed), at most
     * [maxDurationUs] long, scaled down so it reads as an overlay.
     */
    fun placeOverlay(p: Project, asset: MediaAsset, atUs: Long, maxDurationUs: Long, scale: Float = 0.4f): Pair<Project, Clip>? {
        if (asset.type == MediaType.AUDIO) return null
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val base = newClipFor(asset, start)
        val out = if (asset.isStill) maxDurationUs.coerceAtLeast(frame(p.settings.fps)) else minOf(base.sourceOutUs, base.sourceInUs + maxDurationUs.coerceAtLeast(frame(p.settings.fps)))
        val clip = base.copy(sourceOutUs = out, transform = com.amiri.cut.core.model.Props.of("scale" to scale))
        var proj = addAsset(p, asset)
        val t = freeVisualTrack(proj, listOf(TrackKind.OVERLAY), clip.startUs, clip.endUs)
            ?: run { proj = addTrack(proj, TrackKind.OVERLAY); proj.tracks.last { it.kind == TrackKind.OVERLAY } }
        proj = proj.mapTrack(t.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /** Adds a 3 s text clip at [atUs] on a free Text (or Overlay) track, creating one if needed. */
    fun addText(p: Project, atUs: Long, text: String = "Text", durationUs: Long = 3_000_000L): Pair<Project, Clip> {
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val clip = Clip(
            id = newId(), assetId = "", name = text.take(24).ifBlank { "Text" },
            startUs = start, sourceInUs = 0, sourceOutUs = durationUs, text = TextSpec(text = text),
        )
        var proj = p
        val t = freeVisualTrack(proj, listOf(TrackKind.TEXT, TrackKind.OVERLAY), clip.startUs, clip.endUs)
            ?: run { proj = addTrack(proj, TrackKind.TEXT); proj.tracks.last { it.kind == TrackKind.TEXT } }
        proj = proj.mapTrack(t.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /** Adds a 5 s shape layer on a free Text/Overlay track. */
    fun addShape(p: Project, atUs: Long, kind: com.amiri.cut.core.model.ShapeKind, durationUs: Long = 5_000_000L): Pair<Project, Clip> {
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val clip = Clip(
            id = newId(), assetId = "", name = kind.label,
            startUs = start, sourceInUs = 0, sourceOutUs = durationUs, shape = com.amiri.cut.core.model.ShapeSpec(kind),
        )
        var proj = p
        val t = freeVisualTrack(proj, listOf(TrackKind.OVERLAY, TrackKind.TEXT), clip.startUs, clip.endUs)
            ?: run { proj = addTrack(proj, TrackKind.OVERLAY); proj.tracks.last { it.kind == TrackKind.OVERLAY } }
        proj = proj.mapTrack(t.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /**
     * Adds a pen-path shape layer. [points] are vertices (x, y, inX, inY, outX, outY) in
     * normalised canvas coordinates. The anchor is put on the path's centre (with a matching
     * position) so it rotates/scales around itself while staying exactly where it was drawn.
     */
    fun addPathShape(p: Project, atUs: Long, points: List<Float>, closed: Boolean, durationUs: Long = 5_000_000L): Pair<Project, Clip> {
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val n = points.size / 6
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (i in 0 until n) {
            minX = minOf(minX, points[i * 6]); maxX = maxOf(maxX, points[i * 6])
            minY = minOf(minY, points[i * 6 + 1]); maxY = maxOf(maxY, points[i * 6 + 1])
        }
        val cx = if (n > 0) (minX + maxX) / 2f else 0.5f
        val cy = if (n > 0) (minY + maxY) / 2f else 0.5f
        val props = com.amiri.cut.core.model.Props.of(
            "strokeW" to 0.012f, "fillA" to if (closed) 1f else 0f,
            "sr" to 1f, "sg" to 1f, "sb" to 1f,
        )
        val clip = Clip(
            id = newId(), assetId = "", name = if (closed) "Pen shape" else "Pen line",
            startUs = start, sourceInUs = 0, sourceOutUs = durationUs,
            shape = com.amiri.cut.core.model.ShapeSpec(com.amiri.cut.core.model.ShapeKind.PATH, props, points, closed),
            transform = com.amiri.cut.core.model.Props.of("ax" to cx, "ay" to cy, "px" to cx - 0.5f, "py" to cy - 0.5f),
        )
        var proj = p
        val t = freeVisualTrack(proj, listOf(TrackKind.OVERLAY, TrackKind.TEXT), clip.startUs, clip.endUs)
            ?: run { proj = addTrack(proj, TrackKind.OVERLAY); proj.tracks.last { it.kind == TrackKind.OVERLAY } }
        proj = proj.mapTrack(t.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /**
     * Puts a sound (an audio file, or the sound of a video file) on an audio track at [atUs].
     * Uses the first free track named "SFX…" (sound-effect tracks sit below music), else
     * creates a new "SFX" track at the bottom.
     */
    fun placeSound(p: Project, asset: MediaAsset, atUs: Long, preferName: String = "SFX"): Pair<Project, Clip>? {
        if (!asset.hasAudio && asset.type != MediaType.AUDIO) return null
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val clip = newClipFor(asset, start).let { if (asset.type == MediaType.IMAGE) return null else it }
        var proj = addAsset(p, asset)
        val target = proj.tracks.firstOrNull {
            it.kind == TrackKind.AUDIO && !it.locked && it.name.startsWith(preferName) && isFree(it, clip.startUs, clip.endUs)
        }
        val trackId = target?.id ?: run {
            val n = proj.tracks.count { it.kind == TrackKind.AUDIO && it.name.startsWith(preferName) } + 1
            val t = Track(newId(), TrackKind.AUDIO, if (n == 1) preferName else "$preferName $n")
            proj = proj.copy(tracks = proj.tracks + t)
            t.id
        }
        proj = proj.mapTrack(trackId) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /**
     * Detach audio: mutes the video clip and puts its sound (same in/out, speed and audio
     * settings) on a free audio track right below, so it can be edited separately.
     */
    fun detachAudio(p: Project, clipId: String): Pair<Project, Clip>? {
        val track = p.trackOfClip(clipId) ?: return null
        val clip = track.clips.first { it.id == clipId }
        val asset = p.asset(clip.assetId) ?: return null
        if (!track.acceptsVisual || !asset.hasAudio || clip.muted) return null
        val sound = clip.copy(
            id = newId(), name = clip.name + " (audio)",
            transform = com.amiri.cut.core.model.Props(), effects = emptyList(), masks = emptyList(),
            roto = null, tracking = null, follow = null, stab = null, blend = com.amiri.cut.core.model.BlendMode.NORMAL,
            locked = false,
        )
        var proj = p
        val target = proj.tracks.firstOrNull { it.kind == TrackKind.AUDIO && !it.locked && isFree(it, sound.startUs, sound.endUs) }
        val trackId = target?.id ?: run {
            proj = addTrack(proj, TrackKind.AUDIO)
            proj.tracks.last { it.kind == TrackKind.AUDIO }.id
        }
        proj = proj.mapTrack(trackId) { it.withClips(it.clips + sound) }
        proj = proj.mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(muted = true) else it }) }
        return proj to sound
    }

    /** Sets (or clears with null) the transition into [clipId]. */
    fun setTransition(p: Project, clipId: String, tr: com.amiri.cut.core.model.Transition?): Project? {
        val track = p.trackOfClip(clipId) ?: return null
        if (track.locked) return null
        return p.mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(transIn = tr) else it }) }
    }

    /** Puts [tr] on every cut of [trackId] (clips that start exactly where another ends). */
    fun setTransitionAllCuts(p: Project, trackId: String, tr: com.amiri.cut.core.model.Transition?): Project? {
        val track = p.track(trackId) ?: return null
        if (track.locked) return null
        return p.mapTrack(track.id) { t ->
            t.copy(clips = t.clips.map { b ->
                val hasPrev = t.clips.any { it.id != b.id && kotlin.math.abs(it.endUs - b.startUs) <= 1_000 }
                if (hasPrev) b.copy(transIn = tr) else b
            })
        }
    }

    /**
     * Uses [cleaned] (a processed copy of [clipId]'s sound covering its source in..out) for
     * the clip's audio. Audio clips switch to the new file; a video clip is muted and the
     * clean sound is placed on an audio track at the same time (same speed and settings).
     */
    fun useCleanSound(p: Project, clipId: String, cleaned: MediaAsset): Pair<Project, Clip>? {
        val track = p.trackOfClip(clipId) ?: return null
        val clip = track.clips.first { it.id == clipId }
        var proj = addAsset(p, cleaned)
        if (track.kind == TrackKind.AUDIO) {
            val nc = clip.copy(assetId = cleaned.id, sourceInUs = 0, sourceOutUs = clip.sourceOutUs - clip.sourceInUs, name = clip.name.removeSuffix(" (clean)") + " (clean)")
            proj = proj.mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) nc else it }) }
            return proj to nc
        }
        val (dp, sound) = detachAudio(proj, clipId) ?: return null
        val nc = sound.copy(assetId = cleaned.id, sourceInUs = 0, sourceOutUs = sound.sourceOutUs - sound.sourceInUs, name = clip.name + " (clean voice)")
        val tr = dp.trackOfClip(sound.id) ?: return null
        val out = dp.mapTrack(tr.id) { t -> t.copy(clips = t.clips.map { if (it.id == sound.id) nc else it }) }
        return out to nc
    }

    /** Adds an adjustment layer (affects all layers below it) at [atUs]. */
    fun addAdjustment(p: Project, atUs: Long, durationUs: Long = 5_000_000L): Pair<Project, Clip> {
        val start = FrameTime.quantize(atUs.coerceAtLeast(0), p.settings.fps)
        val clip = Clip(
            id = newId(), assetId = "", name = "Adjustment layer",
            startUs = start, sourceInUs = 0, sourceOutUs = durationUs, adjustment = true,
        )
        var proj = p
        val t = freeVisualTrack(proj, listOf(TrackKind.OVERLAY), clip.startUs, clip.endUs)
            ?: run { proj = addTrack(proj, TrackKind.OVERLAY); proj.tracks.last { it.kind == TrackKind.OVERLAY } }
        proj = proj.mapTrack(t.id) { it.withClips(it.clips + clip) }
        return proj to clip
    }

    /** Freeze frame: splits at [atUs] and inserts a still of [durationUs], rippling later clips. */
    fun insertFreeze(p: Project, clipId: String, atUs: Long, still: MediaAsset, durationUs: Long): Pair<Project, Clip>? {
        val track = p.trackOfClip(clipId) ?: return null
        if (track.locked) return null
        val at = FrameTime.quantize(atUs, p.settings.fps)
        var proj = split(p, clipId, at)?.first ?: p
        val src = proj.clip(clipId) ?: return null
        val freeze = Clip(
            id = newId(), assetId = still.id, name = "Freeze · " + src.name,
            startUs = at, sourceInUs = 0, sourceOutUs = durationUs,
            transform = src.transform.shift(-(at - src.startUs)), flipH = src.flipH, flipV = src.flipV,
            blend = src.blend, effects = src.effects.map { it.copy(props = it.props.shift(-(at - src.startUs))) },
        )
        proj = addAsset(proj, still).mapTrack(track.id) { t ->
            t.withClips(t.clips.map { if (it.startUs >= at) it.copy(startUs = it.startUs + durationUs) else it } + freeze)
        }
        return proj to freeze
    }

    /** Reverse: point the clip at a reversed proxy, mirroring its source range. */
    fun swapToReversed(p: Project, clipId: String, reversed: MediaAsset, originalId: String): Project? {
        val track = p.trackOfClip(clipId) ?: return null
        val c = track.clips.first { it.id == clipId }
        val dur = reversed.durationUs
        val updated = c.copy(
            assetId = reversed.id,
            sourceInUs = (dur - c.sourceOutUs).coerceAtLeast(0),
            sourceOutUs = (dur - c.sourceInUs).coerceAtMost(dur),
            reversedFrom = if (c.reversedFrom == null) originalId else null,
            roto = null, tracking = null, stab = null,
        )
        return addAsset(p, reversed).mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
    }

    /** Changes constant speed, keeping the clip start; refuses if it would overlap the next clip. */
    fun setSpeed(p: Project, clipId: String, speed: Float): Project? {
        val (track, clip) = editable(p, clipId) ?: return null
        val updated = clip.copy(speed = speed.coerceIn(0.05f, 16f))
        if (!isFree(track, updated.startUs, updated.endUs, ignoreClipId = clipId)) return null
        return p.mapTrack(track.id) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
    }

    /** Replaces a clip with an updated copy (no overlap check beyond replaceClip's). */
    fun updateClip(p: Project, clipId: String, f: (Clip) -> Clip): Project? {
        val track = p.trackOfClip(clipId) ?: return null
        if (track.locked) return null
        val c = track.clips.first { it.id == clipId }
        if (c.locked) return null
        return replaceClip(p, f(c))
    }

}
