package com.local.personaltoolbox.modules.aov

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AovPurchaseFlowTest {
    private class Host(vararg frames: AovScreen) : AovPurchaseFlow.Host {
        private val frames = frames.toMutableList()
        val taps = mutableListOf<Pair<Int, Int>>()
        var allowCommit = true
        var commits = 0
        var purchases = 0
        var already = 0
        override suspend fun screen(afterMillis: Long) = if (frames.isEmpty()) AovScreen.UNKNOWN else frames.removeAt(0)
        override suspend fun tap(x: Int, y: Int) { taps += x to y }
        override suspend fun commitPurchase(): Boolean { commits++; return allowCommit }
        override suspend fun purchased() { purchases++ }
        override suspend fun alreadyPurchased() { already++ }
    }

    @Test fun verifiedPathPurchasesOnceAndEndsAtSoldState() = runBlocking {
        val host = Host(AovScreen.PROMO, AovScreen.LOBBY, AovScreen.SHOP, AovScreen.ITEMS, AovScreen.GEMS,
            AovScreen.SELECTED, AovScreen.CONFIRM, AovScreen.SUCCESS, AovScreen.SOLD)
        AovPurchaseFlow(host).run()
        assertEquals(1, host.commits); assertEquals(1, host.purchases)
        assertEquals(1, host.taps.count { it == 1192 to 862 })
    }

    @Test fun alreadyBoughtNeverOpensPurchaseDialogOrCountsNewPurchase() = runBlocking {
        val host = Host(AovScreen.SOLD)
        AovPurchaseFlow(host).run()
        assertTrue(host.taps.isEmpty()); assertEquals(0, host.commits); assertEquals(0, host.purchases)
        assertEquals(1, host.already)
    }

    @Test fun unrelatedConfirmationUnknownScreenAndDurableDuplicateAreRefused() = runBlocking {
        for (host in listOf(Host(AovScreen.CONFIRM), Host(AovScreen.UNKNOWN),
                Host(AovScreen.SELECTED, AovScreen.CONFIRM).apply { allowCommit = false })) {
            assertTrue(runCatching { AovPurchaseFlow(host).run() }.isFailure)
            assertFalse(host.taps.contains(1192 to 862)); assertEquals(0, host.purchases)
        }
    }

    @Test fun uncertainPurchaseNeverRetriesOrCounts() = runBlocking {
        val host = Host(AovScreen.SELECTED, AovScreen.CONFIRM, AovScreen.SELECTED)
        assertTrue(runCatching { AovPurchaseFlow(host).run() }.isFailure)
        assertEquals(1, host.taps.count { it == 1192 to 862 })
        assertEquals(1, host.commits); assertEquals(0, host.purchases)
    }
}
