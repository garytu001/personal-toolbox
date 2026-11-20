package com.local.personaltoolbox.accessibility.ads

import java.util.Random
import kotlin.math.roundToLong

/** Bounded rejection sampling; clamping would concentrate rejected samples at the edges. */
internal class AdSkipSampler(private val random: Random = Random()) {
    fun delayMillis(): Long = normal(0.0, 1_000.0, 500.0, 200.0).roundToLong()

    fun point(bounds: TapBounds): TapPoint {
        require(bounds.width >= 4 && bounds.height >= 4)
        // Use the inner 60% of the freshly verified button, never the surrounding screen.
        val left = bounds.left + bounds.width * 0.20
        val right = bounds.right - bounds.width * 0.20
        val top = bounds.top + bounds.height * 0.20
        val bottom = bounds.bottom - bounds.height * 0.20
        return TapPoint(
            normal(left, right, (left + right) / 2, (right - left) / 6).toFloat(),
            normal(top, bottom, (top + bottom) / 2, (bottom - top) / 6).toFloat(),
        )
    }

    private fun normal(min: Double, max: Double, mean: Double, deviation: Double): Double {
        repeat(128) {
            val value = mean + random.nextGaussian() * deviation
            if (value.isFinite() && value >= min && value < max) return value
        }
        return mean // Defensive fallback for a broken RNG, still inside the valid range.
    }
}
