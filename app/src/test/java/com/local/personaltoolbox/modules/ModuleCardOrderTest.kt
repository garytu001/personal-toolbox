package com.local.personaltoolbox.modules

import org.junit.Assert.*
import org.junit.Test

class ModuleCardOrderTest {
    private val shorts = ModuleCard("short_video", 1, listOf(ModuleDefinition("yt_shorts", 1), ModuleDefinition("ig_reels", 2), ModuleDefinition("fb_reels", 3, false)))
    private val ads = ModuleCard("yt_ad_skip", 4, listOf(ModuleDefinition("yt_ad_skip", 4, false)))
    private val cards = listOf(shorts, ads)

    @Test fun mergingUsesEarliestMemberPositionWithoutDuplicateCards() {
        assertEquals(listOf(shorts, ads), ModuleCardOrder.resolve(listOf("ig_reels", "yt_ad_skip", "yt_shorts"), cards))
        assertEquals(listOf(ads, shorts), ModuleCardOrder.resolve(listOf("yt_ad_skip", "ig_reels", "yt_shorts"), cards))
    }
    @Test fun draggingGroupPersistsStableModuleIdsAndRestoresItsPosition() {
        val saved = ModuleCardOrder.flatten(listOf(ads, shorts))
        assertEquals(listOf("yt_ad_skip", "yt_shorts", "ig_reels", "fb_reels"), saved)
        assertEquals(listOf(ads, shorts), ModuleCardOrder.resolve(saved, cards))
    }
    @Test fun unknownIdsAndFreshInstallKeepAllCards() {
        assertEquals(cards, ModuleCardOrder.resolve(listOf("obsolete"), cards))
        assertEquals(cards, ModuleCardOrder.resolve(emptyList(), cards))
    }
}
