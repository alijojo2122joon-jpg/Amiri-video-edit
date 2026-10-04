package com.amiri.cut.core.vision

/**
 * Boykov–Kolmogorov max-flow / min-cut on a 4-connected pixel grid (the algorithm used
 * by GrabCut-style segmentation). Node p = y*w + x. Edge index e = p*4 + d with
 * d = 0 right, 1 down, 2 left, 3 up; the reverse edge is the neighbour's opposite slot.
 *
 * Fill [tr] (terminal: >0 = capacity from SOURCE/foreground, <0 = to SINK/background)
 * and [cap] (pairwise capacities, both directions), call [maxflow], then [isSource].
 */
class GraphCut(val w: Int, val h: Int) {
    val n = w * h
    val tr = FloatArray(n)
    val cap = FloatArray(n * 4)

    private val parent = IntArray(n)          // edge index from node to its parent, or TERMINAL / NONE
    private val inSink = BooleanArray(n)
    private val ts = IntArray(n)
    private val dist = IntArray(n)
    private val nextActive = IntArray(n)
    private var activeFirst = -1
    private var activeLast = -1
    private val queued = BooleanArray(n)
    private var time = 0
    private var orphans = IntArray(1024)
    private var orphanCount = 0
    var flow = 0.0
        private set

    private fun neighbor(p: Int, d: Int): Int {
        val x = p % w
        return when (d) {
            0 -> if (x + 1 < w) p + 1 else -1
            1 -> if (p + w < n) p + w else -1
            2 -> if (x > 0) p - 1 else -1
            else -> if (p - w >= 0) p - w else -1
        }
    }

    private fun sister(e: Int): Int {
        val p = e shr 2; val d = e and 3
        return (neighbor(p, d) shl 2) or ((d + 2) and 3)
    }

    private fun setActive(p: Int) {
        if (queued[p]) return
        queued[p] = true
        nextActive[p] = -1
        if (activeLast >= 0) nextActive[activeLast] = p else activeFirst = p
        activeLast = p
    }

    private fun nextActiveNode(): Int {
        while (activeFirst >= 0) {
            val p = activeFirst
            activeFirst = nextActive[p]
            if (activeFirst < 0) activeLast = -1
            queued[p] = false
            if (parent[p] != NONE) return p
        }
        return -1
    }

    private fun addOrphan(p: Int) {
        if (orphanCount == orphans.size) orphans = orphans.copyOf(orphans.size * 2)
        orphans[orphanCount++] = p
    }

    fun maxflow(): Double {
        // Init trees from terminal capacities.
        for (p in 0 until n) {
            queued[p] = false
            ts[p] = 0
            if (tr[p] > 0f) { parent[p] = TERMINAL; inSink[p] = false; dist[p] = 1; setActive(p) }
            else if (tr[p] < 0f) { parent[p] = TERMINAL; inSink[p] = true; dist[p] = 1; setActive(p) }
            else parent[p] = NONE
        }
        time = 0
        flow = 0.0
        var cur = -1
        while (true) {
            // ── growth ──
            var p = if (cur >= 0 && parent[cur] != NONE) cur else nextActiveNode()
            if (p < 0) break
            cur = -1
            var meet = -1 // edge from source-tree node to sink-tree node
            if (!inSink[p]) {
                for (d in 0..3) {
                    val e = (p shl 2) or d
                    if (cap[e] <= 0f) continue
                    val q = neighbor(p, d); if (q < 0) continue
                    if (parent[q] == NONE) {
                        inSink[q] = false; parent[q] = sister(e); ts[q] = ts[p]; dist[q] = dist[p] + 1; setActive(q)
                    } else if (inSink[q]) { meet = e; break }
                    else if (ts[q] <= ts[p] && dist[q] > dist[p]) { parent[q] = sister(e); ts[q] = ts[p]; dist[q] = dist[p] + 1 }
                }
            } else {
                for (d in 0..3) {
                    val e = (p shl 2) or d
                    val q = neighbor(p, d); if (q < 0) continue
                    val back = sister(e) // q -> p
                    if (cap[back] <= 0f) continue
                    if (parent[q] == NONE) {
                        inSink[q] = true; parent[q] = back; ts[q] = ts[p]; dist[q] = dist[p] + 1; setActive(q)
                    } else if (!inSink[q]) { meet = back; break }
                    else if (ts[q] <= ts[p] && dist[q] > dist[p]) { parent[q] = back; ts[q] = ts[p]; dist[q] = dist[p] + 1 }
                }
            }
            time++
            if (meet < 0) continue
            cur = p
            setActive(p) // keep exploring p later
            // ── augmentation ──
            augment(meet)
            // ── adoption ──
            while (orphanCount > 0) {
                val o = orphans[--orphanCount]
                if (inSink[o]) adoptSink(o) else adoptSource(o)
            }
        }
        return flow
    }

    private fun augment(middle: Int) {
        val ps = middle shr 2
        val pt = neighbor(ps, middle and 3)
        var bottleneck = cap[middle].toDouble()
        // source side
        var i = ps
        while (true) {
            val e = parent[i]
            if (e == TERMINAL) { bottleneck = minOf(bottleneck, tr[i].toDouble()); break }
            bottleneck = minOf(bottleneck, cap[sister(e)].toDouble()) // parent -> i
            i = neighbor(e shr 2, e and 3)
        }
        i = pt
        while (true) {
            val e = parent[i]
            if (e == TERMINAL) { bottleneck = minOf(bottleneck, (-tr[i]).toDouble()); break }
            bottleneck = minOf(bottleneck, cap[e].toDouble()) // i -> parent
            i = neighbor(e shr 2, e and 3)
        }
        val b = bottleneck.toFloat()
        cap[middle] -= b; cap[sister(middle)] += b
        i = ps
        while (true) {
            val e = parent[i]
            if (e == TERMINAL) { tr[i] -= b; if (tr[i] <= 0f) { tr[i] = 0f; parent[i] = ORPHAN; addOrphan(i) }; break }
            val down = sister(e) // parent -> i
            cap[e] += b; cap[down] -= b
            val par = neighbor(e shr 2, e and 3)
            if (cap[down] <= 0f) { cap[down] = 0f; parent[i] = ORPHAN; addOrphan(i) }
            i = par
        }
        i = pt
        while (true) {
            val e = parent[i]
            if (e == TERMINAL) { tr[i] += b; if (tr[i] >= 0f) { tr[i] = 0f; parent[i] = ORPHAN; addOrphan(i) }; break }
            cap[sister(e)] += b; cap[e] -= b
            val par = neighbor(e shr 2, e and 3)
            if (cap[e] <= 0f) { cap[e] = 0f; parent[i] = ORPHAN; addOrphan(i) }
            i = par
        }
        flow += b
    }

    /** Is [p] connected to its terminal through valid parents? Returns path length or -1. */
    private fun originDist(p0: Int): Int {
        var p = p0
        var d = 0
        while (true) {
            if (ts[p] == time) { d += dist[p]; break }
            val e = parent[p]
            d++
            if (e == TERMINAL) { ts[p] = time; dist[p] = 1; break }
            if (e == ORPHAN || e == NONE) return -1
            p = neighbor(e shr 2, e and 3)
        }
        // cache distances along the path
        p = p0
        var dd = d
        while (ts[p] != time) {
            ts[p] = time; dist[p] = dd; dd--
            val e = parent[p]
            p = neighbor(e shr 2, e and 3)
        }
        return d
    }

    private fun adoptSource(o: Int) {
        var best = NONE; var bestD = Int.MAX_VALUE
        for (d in 0..3) {
            val e = (o shl 2) or d
            val q = neighbor(o, d); if (q < 0) continue
            if (parent[q] == NONE || inSink[q]) continue
            if (cap[sister(e)] <= 0f) continue // q -> o must have capacity
            val dd = originDist(q)
            if (dd >= 0 && dd < bestD) { bestD = dd; best = e }
        }
        if (best != NONE) {
            parent[o] = best; ts[o] = time; dist[o] = bestD + 1
            return
        }
        parent[o] = NONE
        for (d in 0..3) {
            val e = (o shl 2) or d
            val q = neighbor(o, d); if (q < 0) continue
            if (parent[q] == NONE || inSink[q]) continue
            if (cap[sister(e)] > 0f) setActive(q)
            val pe = parent[q]
            if (pe != TERMINAL && pe != ORPHAN && pe != NONE && neighbor(pe shr 2, pe and 3) == o) { parent[q] = ORPHAN; addOrphan(q) }
        }
    }

    private fun adoptSink(o: Int) {
        var best = NONE; var bestD = Int.MAX_VALUE
        for (d in 0..3) {
            val e = (o shl 2) or d
            val q = neighbor(o, d); if (q < 0) continue
            if (parent[q] == NONE || !inSink[q]) continue
            if (cap[e] <= 0f) continue // o -> q must have capacity
            val dd = originDist(q)
            if (dd >= 0 && dd < bestD) { bestD = dd; best = e }
        }
        if (best != NONE) {
            parent[o] = best; ts[o] = time; dist[o] = bestD + 1
            return
        }
        parent[o] = NONE
        for (d in 0..3) {
            val e = (o shl 2) or d
            val q = neighbor(o, d); if (q < 0) continue
            if (parent[q] == NONE || !inSink[q]) continue
            if (cap[e] > 0f) setActive(q)
            val pe = parent[q]
            if (pe != TERMINAL && pe != ORPHAN && pe != NONE && neighbor(pe shr 2, pe and 3) == o) { parent[q] = ORPHAN; addOrphan(q) }
        }
    }

    /** After [maxflow]: is pixel p on the foreground (source) side of the cut? */
    fun isSource(p: Int): Boolean = parent[p] != NONE && !inSink[p]

    companion object {
        private const val NONE = -1
        private const val TERMINAL = -2
        private const val ORPHAN = -3
    }
}
