package com.local.personaltoolbox.modules

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleOrderTest {
    private val ids = listOf("yt_shorts", "ig_reels")
    @Test fun firstLaunchUsesRegistrationOrder() {
        assertEquals(ids, ModuleOrder.resolve(null, ids))
    }
    @Test fun restoresUserOrderAfterRestart() {
        assertEquals(listOf("ig_reels", "yt_shorts"), ModuleOrder.resolve("ig_reels,yt_shorts", ids))
    }
    @Test fun newModuleIsAppendedWithoutChangingExistingOrder() {
        assertEquals(listOf("ig_reels", "yt_shorts", "timer"), ModuleOrder.resolve("ig_reels,yt_shorts", ids + "timer"))
    }
    @Test fun unknownAndRepeatedIdsDoNotCreateMissingOrDuplicateCards() {
        assertEquals(listOf("ig_reels", "yt_shorts"), ModuleOrder.resolve("old,ig_reels,ig_reels,", ids))
    }
    @Test fun encodeNormalizesBeforeSaving() {
        assertEquals("ig_reels,yt_shorts", ModuleOrder.encode(listOf(" ig_reels ", "unknown", "ig_reels"), ids))
    }
}
