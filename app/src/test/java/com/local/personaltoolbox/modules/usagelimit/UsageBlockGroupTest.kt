package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageBlockGroupTest {
    @Test fun oneLimitBlocksAllFiveAppsUntilTheSameDeadline() = runTest {
        var foreground = 0
        var saves = 0
        val exits = IntArray(5)
        val group = UsageBlockGroup({ testScheduler.currentTime }, { 0 }, { saves++ })
        group.awaitReady()
        val timers = (0..4).map { i -> UsageLimitTimer(this, if (i == 0) 1_200_000 else 300_000,
            { testScheduler.currentTime }, { foreground == i }, { exits[i]++; true }, {},
            { end -> group.start(end) }) }
        timers.forEach { timer -> backgroundScope.launch {
            group.until.collect { timer.synchronizeBlock(it) }
        } }
        runCurrent()
        // Instagram reaches five minutes while the others have not reached their own limits.
        foreground = 1
        timers[1].observe(true)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(360_000L, group.until.value)
        assertTrue(timers.all { it.isBlocked })
        assertEquals(1, saves)
        timers[1].observe(false)
        for (i in 0..4) {
            foreground = i
            timers[i].observe(true); timers[i].enforceBlock(); timers[i].observe(false)
            advanceTimeBy(1_000); runCurrent()
        }
        assertTrue(exits.all { it >= 1 })
        assertEquals(360_000L, group.until.value)
        advanceTimeBy(55_000); runCurrent()
        assertTrue(timers.none { it.isBlocked })
        foreground = 0
        timers[0].observe(true); timers[0].enforceBlock()
        val before = exits.sum()
        advanceTimeBy(1_199_999); runCurrent()
        assertEquals(before, exits.sum())
        advanceTimeBy(1); runCurrent()
        assertEquals(2, saves)
        assertTrue(timers.all { it.isBlocked })
        timers.forEach { it.pause() }
    }

    @Test fun overlappingRequestsDoNotExtendOrRepersistTheMinute() = runTest {
        val saves = mutableListOf<Long>()
        val group = UsageBlockGroup({ testScheduler.currentTime }, { 0 }, { saves += it })
        group.awaitReady()
        assertEquals(60_000L, group.start(60_000))
        advanceTimeBy(30_000)
        assertEquals(60_000L, group.start(90_000))
        assertEquals(listOf(60_000L), saves)
        advanceTimeBy(30_000)
        assertEquals(120_000L, group.start(120_000))
        assertEquals(2, saves.size)
    }

    @Test fun reconnectLoadsOnceAndKeepsOnlyRemainingTimeIncludingReadLatency() = runTest {
        var reads = 0
        val group = UsageBlockGroup({ testScheduler.currentTime }, {
            reads++; delay(1_000); 20_000
        }, { fail("Restoring must not start another minute") })
        (0..4).map { async { group.awaitReady() } }.awaitAll()
        assertEquals(1, reads)
        assertEquals(20_000L, group.until.value)
        advanceTimeBy(5_000)
        group.awaitReady()
        assertEquals(20_000L, group.start(testScheduler.currentTime + 60_000))
    }

    @Test fun aSharedBlockCancelsOtherAppsOlderUsageDeadlineAndRespectsDisabledGuard() = runTest {
        var allowed = true
        var reachedLimit = 0
        var homes = 0
        val timer = UsageLimitTimer(this, 300_000, { testScheduler.currentTime }, { allowed },
            { homes++; true }, {}, { reachedLimit++ })
        timer.observe(true)
        advanceTimeBy(299_000)
        timer.synchronizeBlock(359_000)
        allowed = false
        timer.enforceBlock()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, reachedLimit); assertEquals(0, homes)
        allowed = true
        timer.enforceBlock()
        assertEquals(1, homes)
        timer.observe(false)
        advanceTimeBy(59_000); runCurrent()
        assertFalse(timer.isBlocked)
    }
}
