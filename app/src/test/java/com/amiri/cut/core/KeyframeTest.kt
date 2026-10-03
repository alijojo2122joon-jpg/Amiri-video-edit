package com.amiri.cut.core

import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.Interp
import com.amiri.cut.core.model.Key
import com.amiri.cut.core.model.Keyframes
import com.amiri.cut.core.model.Param
import com.amiri.cut.core.model.Props
import com.amiri.cut.core.model.SpeedRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeyframeTest {
    @Test fun constantWithoutKeys() { assertEquals(3f, Param(3f).at(123), 0f) }

    @Test fun linearInterpolation() {
        val p = Param(0f, listOf(Key(0, 0f, Interp.LINEAR), Key(1000, 10f, Interp.LINEAR)))
        assertEquals(5f, p.at(500), 1e-4f)
        assertEquals(0f, p.at(-5), 0f)
        assertEquals(10f, p.at(5000), 0f)
    }

    @Test fun holdKeepsValue() {
        val p = Param(0f, listOf(Key(0, 2f, Interp.HOLD), Key(1000, 10f)))
        assertEquals(2f, p.at(999), 0f)
    }

    @Test fun easeIsMonotonicAndSymmetric() {
        var prev = -1f
        for (i in 0..100) {
            val v = Keyframes.cubicBezier(0.42f, 0f, 0.58f, 1f, i / 100f)
            assertTrue(v >= prev - 1e-4f); prev = v
        }
        assertEquals(0.5f, Keyframes.cubicBezier(0.42f, 0f, 0.58f, 1f, 0.5f), 1e-3f)
    }

    @Test fun setAddsKeyWhenAnimated() {
        val p = Param(1f).withKey(0, 1f, 10).withKey(1000, 2f, 10)
        assertEquals(2, p.keys.size)
        val q = p.set(1000, 5f, 10)
        assertEquals(5f, q.at(1000), 0f)
        assertEquals(1, q.withoutKeyNear(0, 10).keys.size)
    }

    @Test fun rampPreservesDuration() {
        val c = Clip("c", "a", "n", 0, 0, 4_000_000, ramp = SpeedRamp(points = listOf(0.3f, 2f, 0.3f)))
        assertEquals(0L, c.sourceTimeAt(0))
        assertTrue(abs(c.sourceTimeAt(c.durationUs) - 4_000_000) < 2000)
        assertTrue(c.sourceTimeAt(1_000_000) < 1_000_000) // slow at start
    }

    @Test fun shiftKeysOnSplit() {
        val c = Clip("c", "a", "n", 0, 0, 4_000_000, transform = Props(mapOf("px" to Param(0f, listOf(Key(2_000_000, 1f))))))
        assertEquals(1_000_000L, c.shiftKeys(-1_000_000).transform["px"]!!.keys[0].t)
    }
}
