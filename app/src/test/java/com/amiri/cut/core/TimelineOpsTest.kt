package com.amiri.cut.core

import com.amiri.cut.core.history.History
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.core.timeline.TimelineOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {

    private fun project(fps: Int = 30) = Project(
        id = "p", name = "t", createdAt = 0, modifiedAt = 0,
        settings = ProjectSettings(1080, 1920, fps, "9:16", "1080p", CanvasBackground.BLACK),
        tracks = Project.defaultTracks(),
    )

    private fun video(id: String, seconds: Int) = MediaAsset(id, "content://x/$id", MediaType.VIDEO, "$id.mp4", seconds * 1_000_000L, 1920, 1080)
    private fun audio(id: String, seconds: Int) = MediaAsset(id, "content://x/$id", MediaType.AUDIO, "$id.m4a", seconds * 1_000_000L, hasAudio = true)
    private fun v1(p: Project) = p.tracks.last { it.kind == TrackKind.VIDEO }

    @Test fun frameMathRoundTrips() {
        for (fps in listOf(24, 25, 30, 50, 60)) {
            for (f in 0L..500L) assertEquals(f, FrameTime.toFrame(FrameTime.fromFrame(f, fps), fps))
        }
        assertEquals("00:01:15", FrameTime.timecode(1_500_000, 30))
    }

    @Test fun appendGoesToV1Sequentially() {
        var p = project()
        p = TimelineOps.appendToMain(p, video("a", 5)).first
        p = TimelineOps.appendToMain(p, video("b", 3)).first
        val clips = v1(p).clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startUs)
        assertEquals(5_000_000L, clips[1].startUs)
        assertEquals(8_000_000L, p.durationUs)
    }

    @Test fun audioGoesToAudioTrack() {
        val p = TimelineOps.appendToMain(project(), audio("m", 10)).first
        assertEquals(TrackKind.AUDIO, p.trackOfClip(p.tracks.flatMap { it.clips }.single().id)!!.kind)
    }

    @Test fun splitIsFrameExactAndContinuous() {
        var p = project()
        val (p1, clip) = TimelineOps.appendToMain(p, video("a", 4))
        p = p1
        val (p2, right) = TimelineOps.split(p, clip.id, 1_234_567)!!
        val left = p2.clip(clip.id)!!
        val at = FrameTime.quantize(1_234_567, 30)
        assertEquals(at, right.startUs)
        assertEquals(left.endUs, right.startUs)
        assertEquals(left.sourceOutUs, right.sourceInUs)
        assertEquals(4_000_000L, p2.durationUs)
    }

    @Test fun splitAtEdgeIsRejected() {
        val (p, clip) = TimelineOps.appendToMain(project(), video("a", 4))
        assertNull(TimelineOps.split(p, clip.id, 0))
        assertNull(TimelineOps.split(p, clip.id, 4_000_000))
    }

    @Test fun rippleDeleteClosesGap() {
        var p = project()
        val (pa, a) = TimelineOps.appendToMain(p, video("a", 2)); p = pa
        p = TimelineOps.appendToMain(p, video("b", 3)).first
        p = TimelineOps.rippleDelete(p, a.id)!!
        assertEquals(0L, v1(p).clips.single().startUs)
        assertEquals(3_000_000L, p.durationUs)
    }

    @Test fun moveRejectsOverlapAndIncompatibleTrack() {
        var p = project()
        val (pa, a) = TimelineOps.appendToMain(p, video("a", 2)); p = pa
        val (pb, b) = TimelineOps.appendToMain(p, video("b", 2)); p = pb
        assertNull(TimelineOps.move(p, b.id, v1(p).id, 1_000_000))
        val audioTrack = p.tracks.first { it.kind == TrackKind.AUDIO }
        assertNull(TimelineOps.move(p, b.id, audioTrack.id, 0))
        val v2 = p.tracks.first { it.name == "V2" }
        val moved = TimelineOps.move(p, b.id, v2.id, 500_000)
        assertNotNull(moved)
        assertEquals(v2.id, moved!!.trackOfClip(b.id)!!.id)
        assertNotNull(a)
    }

    @Test fun trimClampsToSourceAndNeighbours() {
        var p = project()
        val (pa, a) = TimelineOps.appendToMain(p, video("a", 2)); p = pa
        val (pb, b) = TimelineOps.appendToMain(p, video("b", 2)); p = pb
        // Extending A's end into B is clamped at B's start (and at A's source end).
        val endTrim = TimelineOps.trimmedEnd(p, a.id, 3_500_000)!!
        assertEquals(2_000_000L, endTrim.endUs)
        // Trimming B's start forward moves its in-point.
        val startTrim = TimelineOps.trimmedStart(p, b.id, 2_500_000)!!
        assertEquals(FrameTime.quantize(2_500_000, 30), startTrim.startUs)
        assertEquals(startTrim.startUs - 2_000_000L, startTrim.sourceInUs)
        // Can't extend before source frame 0.
        val back = TimelineOps.trimmedStart(p, b.id, 1_000_000)!!
        assertEquals(2_000_000L, back.startUs)
    }

    @Test fun lockedTrackRefusesEdits() {
        var p = project()
        val (pa, a) = TimelineOps.appendToMain(p, video("a", 2)); p = pa
        p = TimelineOps.setTrackLocked(p, v1(p).id, true)
        assertNull(TimelineOps.delete(p, a.id))
        assertNull(TimelineOps.split(p, a.id, 1_000_000))
    }

    @Test fun duplicateFindsNextGap() {
        var p = project()
        val (pa, a) = TimelineOps.appendToMain(p, video("a", 2)); p = pa
        p = TimelineOps.appendToMain(p, video("b", 1)).first
        val (pd, dup) = TimelineOps.duplicate(p, a.id)!!
        assertEquals(3_000_000L, dup.startUs)
        assertEquals(3, v1(pd).clips.size)
    }

    @Test fun snapPicksNearestWithinThreshold() {
        val (v, ok) = TimelineOps.snap(1_010_000, listOf(0, 1_000_000, 2_000_000), 20_000)
        assertTrue(ok); assertEquals(1_000_000L, v)
        assertFalse(TimelineOps.snap(1_500_000, listOf(0, 1_000_000), 20_000).second)
    }

    @Test fun historyUndoRedo() {
        val h = History(limit = 3)
        val p0 = project()
        val p1 = TimelineOps.appendToMain(p0, video("a", 1)).first
        h.record(p0, "add")
        val undone = h.undo(p1)!!
        assertEquals(p0, undone.project)
        val redone = h.redo(p0)!!
        assertEquals(p1, redone.project)
    }

    @Test fun soundEffectsGoToSfxTrackBelowMusic() {
        var p = project()
        val clipVid = video("v", 4).copy(hasAudio = true)
        val r = TimelineOps.placeSound(p, clipVid, 1_000_000L)!!
        p = r.first
        val t = p.trackOfClip(r.second.id)!!
        assertEquals(TrackKind.AUDIO, t.kind)
        assertEquals("SFX", t.name)
        assertEquals(p.tracks.last().id, t.id)
        // A second overlapping effect gets another SFX track.
        val r2 = TimelineOps.placeSound(p, audio("s", 2), 1_500_000L)!!
        assertEquals("SFX 2", r2.first.trackOfClip(r2.second.id)!!.name)
        // A video without sound can't be placed as sound.
        assertNull(TimelineOps.placeSound(p, video("n", 2), 0))
    }

    @Test fun detachAudioMutesVideoAndCopiesTiming() {
        var p = project()
        p = TimelineOps.appendToMain(p, video("a", 5).copy(hasAudio = true)).first
        val c = v1(p).clips[0]
        val (np, sound) = TimelineOps.detachAudio(p, c.id)!!
        assertTrue(np.clip(c.id)!!.muted)
        assertEquals(c.startUs, sound.startUs)
        assertEquals(c.endUs, sound.endUs)
        assertEquals(TrackKind.AUDIO, np.trackOfClip(sound.id)!!.kind)
        assertNull(TimelineOps.detachAudio(np, c.id))
    }

    @Test fun penPathStaysInPlace() {
        val pts = listOf(0.2f, 0.3f, 0f, 0f, 0f, 0f, 0.6f, 0.7f, 0f, 0f, 0f, 0f)
        val (p, c) = TimelineOps.addPathShape(project(), 0, pts, false)
        assertNotNull(p.clip(c.id))
        assertEquals(0.4f, c.transform.at("ax", 0, 0f), 1e-5f)
        assertEquals(-0.1f, c.transform.at("px", 0, 0f), 1e-5f)
        assertEquals(0f, c.shape!!.props.at("fillA", 0, 1f), 0f)
    }

    @Test fun keyMarksShowEase() {
        val prm = com.amiri.cut.core.model.Param(0f, listOf(
            com.amiri.cut.core.model.Key(0, 0f, com.amiri.cut.core.model.Interp.LINEAR),
            com.amiri.cut.core.model.Key(1_000_000, 1f, com.amiri.cut.core.model.Interp.EASE_IN_OUT),
            com.amiri.cut.core.model.Key(2_000_000, 0f, com.amiri.cut.core.model.Interp.HOLD),
        ))
        val c = com.amiri.cut.core.model.Clip("c", "", "c", 0, 0, 3_000_000, transform = com.amiri.cut.core.model.Props(mapOf("px" to prm)))
        val m = c.keyMarks()
        assertEquals(3, m.size)
        assertFalse(m[0].easeIn || m[0].easeOut)
        assertTrue(m[1].easeOut && !m[1].easeIn)
        assertTrue(m[2].easeIn && m[2].hold)
    }
}
