package com.local.personaltoolbox.modules.aov

/** No retries or frame polling. Every action is followed by one verified state observation. */
internal class AovPurchaseFlow(private val host: Host) {
    interface Host {
        suspend fun screen(afterMillis: Long): AovScreen
        suspend fun tap(x: Int, y: Int)
        suspend fun commitPurchase(): Boolean
        suspend fun purchased()
        suspend fun alreadyPurchased()
        suspend fun cleanup()
    }

    suspend fun run() {
        var screen = host.screen(15_000)
        var promos = 0
        var openedDetail = false
        var sentPurchase = false
        var successful = false
        repeat(12) {
            val previousScreen = screen
            when (screen) {
                AovScreen.PROMO -> {
                    check(++promos <= 4) { "too_many_popups" }
                    host.tap(2193, 60)
                }
                AovScreen.LOBBY -> host.tap(1957, 464)
                AovScreen.SHOP -> host.tap(245, 515)
                AovScreen.ITEMS -> host.tap(1511, 151)
                AovScreen.GEMS -> host.tap(1091, 374)
                AovScreen.SELECTED -> {
                    check(!sentPurchase) { "purchase_not_verified" }
                    openedDetail = true
                    host.tap(1823, 990)
                }
                AovScreen.CONFIRM -> {
                    check(openedDetail && !sentPurchase) { "unexpected_confirmation" }
                    check(host.commitPurchase()) { "already_attempted" }
                    // Mark before dispatching, so no code path can send a second purchase.
                    sentPurchase = true
                    host.tap(1192, 862)
                }
                AovScreen.SUCCESS -> {
                    check(sentPurchase && !successful) { "unexpected_success" }
                    successful = true
                    host.purchased()
                    host.tap(1200, 975)
                }
                AovScreen.SOLD -> {
                    if (!successful) {
                        check(!sentPurchase) { "purchase_not_verified" }
                        host.alreadyPurchased()
                    }
                    host.cleanup()
                    return
                }
                AovScreen.UNKNOWN -> error("unrecognized_screen")
            }
            screen = host.screen(if (previousScreen == AovScreen.PROMO) 5_000 else 3_500)
            val expected = when (previousScreen) {
                AovScreen.PROMO -> setOf(AovScreen.PROMO, AovScreen.LOBBY)
                AovScreen.LOBBY -> setOf(AovScreen.SHOP)
                AovScreen.SHOP, AovScreen.ITEMS -> setOf(AovScreen.ITEMS, AovScreen.GEMS, AovScreen.SELECTED, AovScreen.SOLD)
                AovScreen.GEMS -> setOf(AovScreen.SELECTED, AovScreen.SOLD)
                AovScreen.SELECTED -> setOf(AovScreen.CONFIRM)
                AovScreen.CONFIRM -> setOf(AovScreen.SUCCESS)
                AovScreen.SUCCESS -> setOf(AovScreen.SOLD)
                else -> emptySet()
            }
            check(screen in expected) { "unexpected_screen" }
        }
        error("too_many_steps")
    }
}
