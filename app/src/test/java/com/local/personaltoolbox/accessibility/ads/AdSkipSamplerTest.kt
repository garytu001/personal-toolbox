package com.local.personaltoolbox.accessibility.ads

import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AdSkipSamplerTest {
    @Test fun delayIsBoundedAndCenteredWithNormalShapedMass() {
        val sampler = AdSkipSampler(Random(42))
        val values = List(30_000) { sampler.delayMillis() }
        assertTrue(values.all { it in 0..1_000 })
        assertTrue(abs(values.average() - 500) < 8)
        val center = values.count { it in 400..600 }
        val edge = values.count { it in 0..200 }
        assertTrue(center > edge * 3)
    }
    @Test fun coordinatesStayInsideButtonSafeInteriorAndAroundItsCenter() {
        val sampler = AdSkipSampler(Random(3))
        val bounds = TapBounds(800, 400, 1_000, 500)
        val points = List(20_000) { sampler.point(bounds) }
        assertTrue(points.all { bounds.contains(it) && it.x in 840f..960f && it.y in 420f..480f })
        assertTrue(abs(points.map { it.x.toDouble() }.average() - 900) < 1)
        assertTrue(abs(points.map { it.y.toDouble() }.average() - 450) < 1)
        assertTrue(points.count { abs(it.x - 900) < 20 } > points.count { it.x < 860 || it.x > 940 } * 5)
    }
    @Test fun outOfRangeNormalSamplesAreResampledRatherThanClampedToAnEdge() {
        val random = object : Random() {
            var calls = 0
            override fun nextGaussian(): Double = when (calls++) { 0 -> 20.0; 1 -> -20.0; else -> 0.0 }
        }
        assertEquals(500L, AdSkipSampler(random).delayMillis())
        assertEquals(3, random.calls)
    }
    @Test fun invalidGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AdSkipSampler().point(TapBounds(10, 10, 10, 20)) }
    }
}
