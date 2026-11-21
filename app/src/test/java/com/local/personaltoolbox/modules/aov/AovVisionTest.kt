package com.local.personaltoolbox.modules.aov

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class AovVisionTest {
    private val vision = AovVision(listOf("src/main/assets/aov/asus_2400x1080_v164111.mask",
        "app/src/main/assets/aov/asus_2400x1080_v164111.mask").map(::File).first { it.exists() }.readLines())

    private fun pixels(scene: String): IntArray {
        val result = IntArray(2400 * 1080)
        GZIPInputStream(javaClass.getResourceAsStream("/aov/$scene.pixels.gz")!!).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('|')
                val x = parts[0].toInt(); val y = parts[1].toInt()
                val w = parts[2].toInt(); val h = parts[3].toInt()
                for (dy in 0 until h) for (dx in 0 until w) {
                    val i = (dy * w + dx) * 8
                    result[(y + dy) * 2400 + x + dx] = parts[4].substring(i, i + 8).toLong(16).toInt()
                }
            }
        }
        return result
    }

    @Test fun recognizesRealPhoneCapturesIncludingPurchasedVersusAvailable() {
        mapOf("current" to AovScreen.LOBBY, "shop" to AovScreen.SHOP, "items" to AovScreen.ITEMS,
            "gems" to AovScreen.GEMS, "crystal" to AovScreen.SELECTED, "confirm" to AovScreen.CONFIRM,
            "purchased" to AovScreen.SUCCESS, "sold" to AovScreen.SOLD, "login" to AovScreen.PROMO,
            "promo_second" to AovScreen.PROMO)
            .forEach { (scene, expected) -> assertEquals(scene, expected, vision.recognize(2400, 1080, pixels(scene))) }
    }

    @Test fun refusesChangedPriceUnreadableLimitAndUnsupportedScreenSize() {
        val selected = pixels("crystal")
        for (y in 971 until 1017) for (x in 1765 until 1888) selected[y * 2400 + x] = 0
        assertEquals(AovScreen.UNKNOWN, vision.recognize(2400, 1080, selected))
        val badLimit = pixels("crystal")
        for (y in 339 until 363) for (x in 1898 until 1914) badLimit[y * 2400 + x] = 0
        assertEquals(AovScreen.UNKNOWN, vision.recognize(2400, 1080, badLimit))
        assertEquals(AovScreen.UNKNOWN, vision.recognize(1080, 2400, IntArray(2400 * 1080)))
        assertEquals(AovScreen.UNKNOWN, vision.recognize(2400, 1080, IntArray(2400 * 1080)))
    }
}
