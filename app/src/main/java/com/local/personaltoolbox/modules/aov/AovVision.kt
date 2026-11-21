package com.local.personaltoolbox.modules.aov

internal enum class AovScreen { LOBBY, PROMO, SHOP, ITEMS, GEMS, SELECTED, CONFIRM, SUCCESS, SOLD, UNKNOWN }

/** Deliberately restricted to the screen size and game version verified on the user's phone. */
internal class AovVision(profile: List<String>) {
    var lastScores: Map<String, Double> = emptyMap()
        private set
    private data class Feature(val x: Int, val y: Int, val width: Int, val height: Int, val threshold: Int, val mask: String)
    private val features = profile.filter { it.isNotBlank() }.associate { line ->
        val p = line.split('|')
        require(p.size == 7)
        p[0] to Feature(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6]).also {
            require(it.mask.length == it.width * it.height && it.mask.any { bit -> bit == '1' })
        }
    }

    fun recognize(width: Int, height: Int, pixels: IntArray): AovScreen {
        val scores = mutableMapOf<String, Double>()
        lastScores = scores
        if (width != 2400 || height != 1080 || pixels.size != width * height) return AovScreen.UNKNOWN
        fun has(name: String): Boolean {
            val f = features.getValue(name)
            var different = 0
            var foreground = 0
            for (y in 0 until f.height) for (x in 0 until f.width) {
                val color = pixels[(f.y + y) * width + f.x + x]
                val r = (color shr 16) and 255
                val g = (color shr 8) and 255
                val b = color and 255
                val visible = (r * 299 + g * 587 + b * 114) / 1000 >= f.threshold
                val expected = f.mask[y * f.width + x] == '1'
                if (visible || expected) foreground++
                if (visible != expected) different++
            }
            val score = if (foreground == 0) 1.0 else different.toDouble() / foreground
            scores[name] = score
            // Thin lobby labels vary slightly between compositor captures; purchase guards remain strict.
            val tolerance = if (name.startsWith("lobby_")) 0.20 else 0.10
            return foreground > 0 && score <= tolerance
        }
        return when {
            has("success_header") && has("success_name") -> AovScreen.SUCCESS
            has("detail_header") && has("detail_name") && has("detail_price") && has("detail_buy") -> AovScreen.CONFIRM
            has("promo_close") && has("promo_checkbox_label") -> AovScreen.PROMO
            has("shop_header") && has("side_name") && has("limit_one") && !has("limit_zero") -> AovScreen.SOLD
            has("shop_header") && has("side_name") && has("limit_zero") && !has("limit_one") && has("buy_price") -> AovScreen.SELECTED
            has("shop_header") && has("side_name") -> AovScreen.UNKNOWN
            has("shop_header") && has("gem_selected") && has("card_name") && has("card_price") -> AovScreen.GEMS
            has("shop_header") && has("gem_selected") -> AovScreen.UNKNOWN
            has("shop_header") && has("items_gem_tab") -> AovScreen.ITEMS
            has("shop_header") -> AovScreen.SHOP
            has("lobby_shop") && has("lobby_bag") -> AovScreen.LOBBY
            else -> AovScreen.UNKNOWN
        }
    }
}
