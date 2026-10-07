package com.amiri.cut.core

import com.amiri.cut.core.anim.ClipAnims
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.ClipAnim
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.core.timeline.TimelineOps
import com.amiri.cut.export.EncoderCaps
import com.amiri.cut.export.ExportQuality
import com.amiri.cut.export.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAnimsTest {
    private val d = 4_000_000L

    @Test fun noAnimationIsIdentity() {
        assertTrue(ClipAnims.xf(null, 1_000_000L, d).identity)
        assertFalse(ClipAnims.active(ClipAnim()))
        assertTrue(ClipAnims.xf(ClipAnim(), 1_000_000L, d).identity)
    }

    @Test fun everyEntranceStartsAwayAndSettles() {
        for (spec in ClipAnims.IN) {
            val a = ClipAnim(inId = spec.id, inDur = 0.5f)
            assertTrue(spec.id, ClipAnims.active(a))
            assertFalse("${spec.id} must move at t=0", ClipAnims.xf(a, 0L, d).identity)
            assertTrue("${spec.id} must be at rest after its duration", ClipAnims.xf(a, 2_000_000L, d).identity)
        }
    }

    @Test fun everyExitEndsAwayAndStartsAtRest() {
        for (spec in ClipAnims.IN) {
            val a = ClipAnim(outId = spec.id, outDur = 0.5f)
            assertTrue("${spec.id} exit must be at rest before it starts", ClipAnims.xf(a, 1_000_000L, d).identity)
            assertFalse("${spec.id} exit must be away at the end", ClipAnims.xf(a, d, d).identity)
        }
    }

    @Test fun fadeAlphaRampsLinearly() {
        val a = ClipAnim(inId = "fade", inDur = 1f)
        assertEquals(0f, ClipAnims.xf(a, 0L, d).alpha, 1e-4f)
        assertEquals(0.5f, ClipAnims.xf(a, 500_000L, d).alpha, 1e-3f)
        assertEquals(1f, ClipAnims.xf(a, 1_000_000L, d).alpha, 1e-4f)
        val out = ClipAnim(outId = "fade", outDur = 1f)
        assertEquals(0f, ClipAnims.xf(out, d, d).alpha, 1e-4f)
    }

    @Test fun slowZoomCoversTheWholeClip() {
        val a = ClipAnim(comboId = "kenBurnsIn")
        assertEquals(1f, ClipAnims.xf(a, 0L, d).sx, 1e-4f)
        assertEquals(1.07f, ClipAnims.xf(a, d / 2, d).sx, 1e-3f)
        assertEquals(1.14f, ClipAnims.xf(a, d, d).sx, 1e-3f)
    }

    @Test fun shortClipsStayFinite() {
        val a = ClipAnim(inId = "pop", inDur = 0.5f, outId = "spin", outDur = 0.5f, comboId = "shake")
        for (t in 0..600_000 step 25_000) {
            val x = ClipAnims.xf(a, t.toLong(), 600_000L)
            assertTrue(x.sx.isFinite() && x.sy.isFinite() && x.rot.isFinite() && x.dx.isFinite())
            assertTrue(x.alpha in 0f..1.0001f)
        }
    }

    @Test fun splitKeepsEntranceLeftAndExitRight() {
        var p = Project(
            id = "p", name = "t", createdAt = 0, modifiedAt = 0,
            settings = ProjectSettings(1080, 1920, 30, "9:16", "1080p", CanvasBackground.BLACK),
            tracks = Project.defaultTracks(),
        )
        val (p1, clip) = TimelineOps.appendToMain(p, MediaAsset("a", "content://x/a", MediaType.VIDEO, "a.mp4", 4_000_000L, 1920, 1080))
        p = TimelineOps.updateClip(p1, clip.id) { it.copy(anim = ClipAnim(inId = "zoomIn", outId = "fade", comboId = "rock")) }!!
        val (p2, right) = TimelineOps.split(p, clip.id, 2_000_000L)!!
        val left = p2.clip(clip.id)!!
        assertNotNull(left.anim)
        assertEquals("zoomIn", left.anim!!.inId)
        assertEquals("None", left.anim!!.outId)
        assertEquals("None", right.anim!!.inId)
        assertEquals("fade", right.anim!!.outId)
        assertEquals("rock", right.anim!!.comboId)
    }

    @Test fun exportBitrateTiersAreOrderedAndSane() {
        val q = ExportQuality.entries.map { EncoderCaps.bitrate(1080, 1920, 30, it, VideoCodec.H264) }
        assertEquals(q.sorted(), q)
        val high = EncoderCaps.bitrate(1080, 1920, 30, ExportQuality.HIGH, VideoCodec.H264)
        assertTrue("1080p30 High ≈ 10.6 Mbps, was $high", high in 9_500_000..12_000_000)
        assertTrue(EncoderCaps.bitrate(1080, 1920, 30, ExportQuality.HIGH, VideoCodec.HEVC) < high)
        assertTrue(EncoderCaps.bitrate(1080, 1920, 60, ExportQuality.HIGH, VideoCodec.H264) in high + 1..high * 2)
    }

    @Test fun levelsMatchTheStandardTables() {
        // H.264: 720p30 → 3.1, 1080p30 → 4, 1080p60 → 4.2, 4K30 → 5.1, 4K60 → 5.2
        assertEquals(0x200, EncoderCaps.level(VideoCodec.H264, 720, 1280, 30, 5_000_000))
        assertEquals(0x800, EncoderCaps.level(VideoCodec.H264, 1080, 1920, 30, 16_000_000))
        assertEquals(0x2000, EncoderCaps.level(VideoCodec.H264, 1920, 1080, 60, 25_000_000))
        assertEquals(0x8000, EncoderCaps.level(VideoCodec.H264, 3840, 2160, 30, 50_000_000))
        assertEquals(0x10000, EncoderCaps.level(VideoCodec.H264, 3840, 2160, 60, 80_000_000))
        // HEVC Main tier: 1080p30 → 4 (0x400), 4K30 → 5 (0x4000)
        assertEquals(0x400, EncoderCaps.level(VideoCodec.HEVC, 1920, 1080, 30, 8_000_000))
        assertEquals(0x4000, EncoderCaps.level(VideoCodec.HEVC, 3840, 2160, 30, 24_000_000))
    }
}
