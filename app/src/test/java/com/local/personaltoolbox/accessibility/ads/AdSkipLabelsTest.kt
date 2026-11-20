package com.local.personaltoolbox.accessibility.ads

import org.junit.Assert.*
import org.junit.Test

class AdSkipLabelsTest {
    private fun ready(id: String = "", vararg labels: String, enabled: Boolean = true, visible: Boolean = true,
                      clickable: Boolean = true, buttonLike: Boolean = true) =
        AdSkipLabels.ready(id, labels.toList(), enabled, visible, clickable, buttonLike)

    @Test fun recognizesKnownSkipButtonIdsAndSupportedLabels() {
        assertTrue(ready("skip_ad_button", "略過"))
        assertTrue(ready("skip_ad_button_text", "Skip"))
        assertTrue(ready("", "Skip ad"))
        assertTrue(ready("", "SKIP ADS ›"))
        assertTrue(ready("", "略過廣告"))
        assertTrue(ready("", "跳过广告"))
    }
    @Test fun countdownAndNumericChildPreventPrematureClick() {
        listOf("Skip ad in 5", "Skip after 3 seconds", "5 秒後略過", "5", "Ad remaining").forEach {
            assertFalse(it, ready("skip_ad_button", it))
        }
        assertFalse(ready("skip_ad_button", "Skip ad", "3"))
    }
    @Test fun premiumUpsellsNeverBecomeSkipTargets() {
        listOf("Skip ads with Premium", "Try ad-free", "免廣告", "免費試用").forEach {
            assertFalse(it, ready("skip_ad_button", it))
        }
    }
    @Test fun hiddenDisabledOrUnclickableControlsAreIgnored() {
        assertFalse(ready("skip_ad_button", "Skip ad", enabled = false))
        assertFalse(ready("skip_ad_button", "Skip ad", visible = false))
        assertFalse(ready("skip_ad_button", "Skip ad", clickable = false))
    }
    @Test fun genericSkipAndOrdinaryVideoTitlesAreNotEnough() {
        assertFalse(ready("", "Skip"))
        assertFalse(ready("", "略過"))
        assertFalse(ready("", "Skip ads (Remix)"))
        assertFalse(ready("", "Skip ads", buttonLike = false))
    }
}
