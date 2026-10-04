package com.amiri.cut.core

import com.amiri.cut.core.vision.GraphCut
import com.amiri.cut.core.vision.Segment
import com.amiri.cut.core.vision.SmartRoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VisionTest {
    /** Reference Edmonds-Karp on a dense matrix (small graphs only). */
    private fun refFlow(w: Int, h: Int, tr: FloatArray, cap: FloatArray): Double {
        val n = w * h; val s = n; val t = n + 1; val N = n + 2
        val c = Array(N) { DoubleArray(N) }
        for (p in 0 until n) {
            if (tr[p] > 0) c[s][p] += tr[p].toDouble() else c[p][t] += -tr[p].toDouble()
            val x = p % w
            if (x + 1 < w) c[p][p + 1] += cap[p * 4].toDouble()
            if (p + w < n) c[p][p + w] += cap[p * 4 + 1].toDouble()
            if (x > 0) c[p][p - 1] += cap[p * 4 + 2].toDouble()
            if (p - w >= 0) c[p][p - w] += cap[p * 4 + 3].toDouble()
        }
        var flow = 0.0
        while (true) {
            val prev = IntArray(N) { -1 }; prev[s] = s
            val q = ArrayDeque<Int>(); q.add(s)
            while (q.isNotEmpty() && prev[t] < 0) { val u = q.removeFirst(); for (v in 0 until N) if (prev[v] < 0 && c[u][v] > 1e-9) { prev[v] = u; q.add(v) } }
            if (prev[t] < 0) break
            var b = Double.MAX_VALUE; var v = t
            while (v != s) { b = minOf(b, c[prev[v]][v]); v = prev[v] }
            v = t
            while (v != s) { c[prev[v]][v] -= b; c[v][prev[v]] += b; v = prev[v] }
            flow += b
        }
        return flow
    }

    @Test fun graphCutMatchesReference() {
        val rnd = Random(7)
        repeat(60) {
            val w = 2 + rnd.nextInt(6); val h = 2 + rnd.nextInt(6)
            val g = GraphCut(w, h)
            for (p in 0 until w * h) g.tr[p] = (rnd.nextFloat() * 20 - 10).let { if (rnd.nextInt(4) == 0) 0f else it }
            for (p in 0 until w * h) { val x = p % w
                if (x + 1 < w) { g.cap[p * 4] = rnd.nextFloat() * 6; g.cap[(p + 1) * 4 + 2] = rnd.nextFloat() * 6 }
                if (p + w < w * h) { g.cap[p * 4 + 1] = rnd.nextFloat() * 6; g.cap[(p + w) * 4 + 3] = rnd.nextFloat() * 6 } }
            val ref = refFlow(w, h, g.tr.copyOf(), g.cap.copyOf())
            g.maxflow()
            assertEquals(ref, g.flow, 1e-3)
        }
    }

    @Test fun segmentsCircleFromScribble() {
        val w = 120; val h = 90
        val rnd = Random(3)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inside = (x - 60) * (x - 60) + (y - 45) * (y - 45) < 28 * 28
            val n = rnd.nextInt(30)
            if (inside) (0xFF shl 24) or ((200 + n / 2) shl 16) or ((90 + n) shl 8) or (60 + n)
            else { val stripe = if ((x / 8) % 2 == 0) 40 else 70; (0xFF shl 24) or ((stripe + n) shl 16) or ((120 + n) shl 8) or (150 + n) }
        }
        val img = Segment.Image.fromArgb(px, w, h)
        val seeds = ByteArray(w * h)
        for (x in 45..75) for (y in 44..46) seeds[y * w + x] = Segment.FG       // a short stroke inside
        for (p in seeds.indices) { val x = p % w; val y = p / w; if (x < 3 || y < 3 || x >= w - 3 || y >= h - 3) seeds[p] = Segment.BG }
        val m = Segment.cut(img, seeds)
        var inter = 0; var uni = 0
        for (p in m.indices) { val x = p % w; val y = p / w
            val gt = (x - 60) * (x - 60) + (y - 45) * (y - 45) < 28 * 28
            if (gt && m[p]) inter++; if (gt || m[p]) uni++ }
        val iou = inter.toDouble() / uni
        println("circle IoU = $iou")
        assertTrue(iou > 0.95)
    }

    @Test fun guidedFilterKeepsRange() {
        val w = 40; val h = 30
        val g = FloatArray(w * h) { if (it % w < 20) 0.1f else 0.9f }
        val inp = FloatArray(w * h) { if (it % w < 21) 0f else 1f }
        val o = Segment.guidedFilter(g, inp, w, h, 4, 1e-3f)
        assertTrue(o.all { it in 0f..1f })
        // edge snaps to the guide edge at x=20
        assertTrue(o[10 * w + 19] < 0.2f && o[10 * w + 20] > 0.6f)
    }

    /** Textured blob (ellipse) on a textured background; centre/scale configurable. */
    private fun scene(w: Int, h: Int, cx: Float, cy: Float, r: Float, seed: Int): Pair<IntArray, BooleanArray> {
        val rnd = Random(seed)
        val gt = BooleanArray(w * h)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val dx = (x - cx) / r; val dy = (y - cy) / (r * 1.3f)
            val inside = dx * dx + dy * dy < 1f
            gt[i] = inside
            val n = rnd.nextInt(25)
            if (inside) { val t = if (((x - cx + 1000).toInt() / 5 + (y - cy + 1000).toInt() / 5) % 2 == 0) 0 else 40
                (0xFF shl 24) or ((190 + t / 2 + n / 2) shl 16) or ((80 + t + n) shl 8) or (50 + n) }
            else { val t = if ((x / 9 + y / 13) % 2 == 0) 0 else 50
                (0xFF shl 24) or ((40 + t + n) shl 16) or ((110 + t / 2 + n) shl 8) or (140 + n) }
        }
        return px to gt
    }
    private fun iou(a: FloatArray, gt: BooleanArray): Double {
        var i = 0; var u = 0
        for (p in gt.indices) { val m = a[p] > 0.5f; if (m && gt[p]) i++; if (m || gt[p]) u++ }
        return i.toDouble() / u
    }

    @Test fun smartStrokeSnapsToObject() {
        val w = 640; val h = 360
        val (px, gt) = scene(w, h, 300f, 180f, 90f, 1)
        val img = Segment.Image.fromArgb(px, w, h)
        val st = BooleanArray(w * h)
        for (x in 260..340) for (y in 175..185) st[y * w + x] = true
        val t0 = System.nanoTime()
        val m = SmartRoto.stroke(img, null, st, add = true)
        println("stroke ms = ${(System.nanoTime() - t0) / 1_000_000}  IoU = ${iou(m, gt)}")
        assertTrue(iou(m, gt) > 0.95)
        // Remove stroke out on the right half: should drop nothing outside, keep IoU on left.
        val rm = BooleanArray(w * h)
        for (x in 500..600) for (y in 20..40) rm[y * w + x] = true
        val m2 = SmartRoto.stroke(img, m, rm, add = false)
        assertTrue(iou(m2, gt) > 0.95)
    }

    @Test fun smartTrackFollowsMovingObject() {
        val w = 640; val h = 360
        var (px, gt) = scene(w, h, 300f, 180f, 90f, 1)
        val img0 = Segment.Image.fromArgb(px, w, h)
        val a0 = FloatArray(w * h) { if (gt[it]) 1f else 0f }
        val tr = SmartRoto.startTracker(img0, a0)
        var last = 0.0
        var tot = 0L
        for (k in 1..8) {
            val s = scene(w, h, 300f + 9f * k, 180f + 3f * k, 90f * (1f + 0.015f * k), 1 + k)
            val t0 = System.nanoTime()
            val m = SmartRoto.track(tr, Segment.Image.fromArgb(s.first, w, h))
            tot += System.nanoTime() - t0
            last = iou(m, s.second)
        }
        println("track avg ms = ${tot / 8 / 1_000_000}  final IoU = $last")
        assertTrue(last > 0.93)
    }
}
