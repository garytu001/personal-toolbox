package com.local.personaltoolbox.accessibility.ads

import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AdSkipSamplerTest {
    @Test fun normalDelayIsBoundedToOneHundredMilliseconds() {
        val sampler = AdSkipSampler(Random(42))
        val values = List(30_000) { sampler.delayMillis() }
        assertTrue(values.all { it in 0..100 })
        assertTrue(abs(values.average() - 50) < 0.8)
        assertTrue(values.count { it in 40..60 } > values.count { it in 0..20 } * 3)
    }
    @Test fun outliersAreResampledInsteadOfClamped() {
        val random = object : Random() {
            var calls = 0
            override fun nextGaussian(): Double = when (calls++) { 0 -> 20.0; 1 -> -20.0; else -> 0.0 }
        }
        assertEquals(50L, AdSkipSampler(random).delayMillis())
        assertEquals(3, random.calls)
    }
    @Test fun brokenGeneratorStillReturnsSafeDelay() {
        val random = object : Random() { override fun nextGaussian() = Double.NaN }
        assertEquals(50L, AdSkipSampler(random).delayMillis())
    }
}
