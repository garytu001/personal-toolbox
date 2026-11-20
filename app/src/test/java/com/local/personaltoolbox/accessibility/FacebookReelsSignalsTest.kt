package com.local.personaltoolbox.accessibility

import org.junit.Assert.*
import org.junit.Test

class FacebookReelsSignalsTest {
    @Test fun explicitSelectedReelsTabMatches() {
        assertTrue(FacebookReelsSignals(true, false, false, false, false).matches())
    }
    @Test fun fullScreenViewerNeedsHeadingPagerAndStackedActions() {
        assertTrue(FacebookReelsSignals(false, true, true, true, false).matches())
        assertFalse(FacebookReelsSignals(false, false, true, true, false).matches())
        assertFalse(FacebookReelsSignals(false, true, false, true, false).matches())
        assertFalse(FacebookReelsSignals(false, true, true, false, false).matches())
    }
    @Test fun homeFeedReelsShelfIsNotEnough() {
        assertFalse(FacebookReelsSignals(false, true, false, false, false).matches())
    }
    @Test fun storiesAlwaysTakePrecedence() {
        assertFalse(FacebookReelsSignals(true, true, true, true, true).matches())
    }
}
