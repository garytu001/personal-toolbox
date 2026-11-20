package com.local.personaltoolbox.accessibility.ads

import java.util.Random
import kotlin.math.roundToLong

/** Rejection sampling preserves a truncated normal instead of piling samples onto its edges. */
internal class AdSkipSampler(private val random: Random = Random()) {
    fun delayMillis(): Long {
        repeat(128) {
            val value = 50.0 + random.nextGaussian() * 20.0
            if (value.isFinite() && value >= 0 && value <= 100) return value.roundToLong()
        }
        return 50
    }
}
