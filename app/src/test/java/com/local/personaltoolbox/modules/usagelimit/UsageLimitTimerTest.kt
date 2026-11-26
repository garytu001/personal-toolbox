package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageLimitTimerTest {
    private class Host {
        var allowed = true
        var acceptsHome = true
        var exits = 0
        var counts = 0
        var blocks = 0
        fun timer(scope: CoroutineScope, now: () -> Long, minutes: Int = 5, restored: Long = 0) = UsageLimitTimer(
            scope, minutes * 60_000L, now, { allowed }, { exits++; acceptsHome }, { counts++ },
            { blocks++ }, initialBlockMillis = restored)
    }

    @Test fun staticForegroundStartsOneMinuteBlockExactlyAtFiveMinutes() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(299_999); runCurrent()
        assertFalse(timer.isBlocked); assertEquals(0, host.exits)
        advanceTimeBy(1); runCurrent()
        assertTrue(timer.isBlocked); assertEquals(1, host.exits); assertEquals(1, host.blocks)
        assertEquals(1, host.counts)
        timer.observe(false)
        advanceTimeBy(59_999); runCurrent()
        assertTrue(timer.isBlocked)
        advanceTimeBy(1); runCurrent()
        assertFalse(timer.isBlocked)
    }

    @Test fun youtubeRetainsTwentyMinuteLimit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, minutes = 20)
        timer.observe(true)
        advanceTimeBy(1_199_999); runCurrent()
        assertFalse(timer.isBlocked)
        advanceTimeBy(1); runCurrent()
        assertTrue(timer.isBlocked); assertEquals(1, host.exits)
        timer.pause()
    }

    @Test fun everyReentryDuringBlockReturnsHomeWithoutExtendingExpiry() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        repeat(5) {
            timer.observe(false)
            advanceTimeBy(10_000)
            timer.observe(true)
            timer.enforceBlock()
        }
        assertEquals(6, host.exits); assertEquals(1, host.blocks)
        timer.observe(false)
        advanceTimeBy(10_000); runCurrent()
        assertFalse(timer.isBlocked)
        timer.observe(true); timer.enforceBlock()
        assertEquals(6, host.exits)
        timer.pause()
    }

    @Test fun rapidReentryHasOneDeferredHomeAfterCooldownAndCancelsWhenLeaving() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, restored = 60_000)
        timer.observe(true); timer.enforceBlock()
        assertEquals(1, host.exits)
        timer.observe(false)
        advanceTimeBy(100)
        timer.observe(true)
        repeat(10) { timer.enforceBlock() }
        advanceTimeBy(349); runCurrent()
        assertEquals(1, host.exits)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, host.exits)
        timer.enforceBlock()
        timer.observe(false)
        advanceTimeBy(450); runCurrent()
        assertEquals(2, host.exits)
    }

    @Test fun leavingOrLockingBeforeLimitResetsContinuousVisit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(299_999)
        timer.observe(false)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(0, host.blocks)
        timer.observe(true)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(0, host.blocks)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.blocks)
        timer.pause()
    }

    @Test fun ordinaryTapsAndScrollsDoNotRestartUsageDeadline() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        repeat(5) { advanceTimeBy(60_000); timer.observe(true); timer.enforceBlock() }
        runCurrent()
        assertEquals(1, host.blocks); assertEquals(1, host.exits)
        timer.pause()
    }

    @Test fun pauseDuringBanPreservesRemainingTimeAndAllowsUseAfterExpiry() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, restored = 60_000)
        timer.observe(true); timer.enforceBlock()
        timer.pause()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(timer.isBlocked)
        timer.observe(true); timer.enforceBlock()
        assertEquals(2, host.exits)
        timer.observe(false)
        advanceTimeBy(30_000); runCurrent()
        timer.observe(true); timer.enforceBlock()
        assertFalse(timer.isBlocked); assertEquals(2, host.exits)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, host.blocks); assertEquals(3, host.exits)
        timer.pause()
    }

    @Test fun lastMomentForegroundAndSwitchChecksPreventActionsOrNewBan() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(299_999)
        host.allowed = false
        advanceTimeBy(1); runCurrent()
        assertEquals(0, host.blocks); assertEquals(0, host.exits)
        timer.pause()
    }

    @Test fun disabledLockedOrDifferentForegroundCannotReceiveDeferredHome() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, restored = 60_000)
        timer.observe(true); timer.enforceBlock()
        timer.enforceBlock()
        host.allowed = false
        advanceTimeBy(450); runCurrent()
        assertEquals(1, host.exits)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, host.exits)
        assertTrue(timer.isBlocked)
        timer.pause()
    }

    @Test fun failedHomeNeverCountsButNewForegroundEventCanRetryDuringBlock() = runTest {
        val host = Host().apply { acceptsHome = false }
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        assertTrue(timer.isBlocked); assertEquals(1, host.exits); assertEquals(0, host.counts)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, host.exits) // No polling or timed repeating HOME loop.
        host.acceptsHome = true
        timer.enforceBlock()
        assertEquals(2, host.exits); assertEquals(1, host.counts)
        timer.pause()
    }

    @Test fun expiryWhileAppRemainsForegroundStartsFullNewVisit() = runTest {
        val host = Host().apply { acceptsHome = false }
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertFalse(timer.isBlocked)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(1, host.blocks)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, host.blocks)
        timer.pause()
    }

    @Test fun reentryAtExactExpiryDoesNotSendHomeAndStartsFreshVisit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, restored = 60_000)
        advanceTimeBy(60_000)
        timer.observe(true); timer.enforceBlock()
        runCurrent()
        assertEquals(0, host.exits)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(0, host.blocks)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.blocks)
        timer.pause()
    }

    @Test fun independentAppsHaveIndependentUsageAndOneMinuteBlocks() = runTest {
        val firstHost = Host()
        val otherHost = Host()
        val first = firstHost.timer(this, { testScheduler.currentTime }, restored = 60_000)
        val other = otherHost.timer(this, { testScheduler.currentTime })
        first.observe(true); first.enforceBlock(); first.observe(false)
        other.observe(true); other.enforceBlock()
        advanceTimeBy(60_000); runCurrent()
        assertFalse(first.isBlocked); assertFalse(other.isBlocked)
        assertEquals(1, firstHost.exits); assertEquals(0, otherHost.exits)
        advanceTimeBy(240_000); runCurrent()
        assertEquals(1, otherHost.exits)
        other.pause()
    }

    @Test fun stoppingServiceCancelsDeadlinesAndDeferredHome() = runTest {
        val service = CoroutineScope(coroutineContext + Job())
        val host = Host()
        val timer = host.timer(service, { testScheduler.currentTime }, restored = 60_000)
        timer.observe(true); timer.enforceBlock(); timer.enforceBlock()
        service.cancel()
        advanceTimeBy(600_000); runCurrent()
        timer.observe(true); timer.enforceBlock()
        assertEquals(1, host.exits); assertEquals(0, host.blocks)
    }

    @Test fun wakingAfterBlockExpiredDoesNotLetDelayedExpiryResetNewVisit() = runTest {
        var sleepElapsed = 0L
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime + sleepElapsed }, restored = 60_000)
        timer.observe(true); timer.enforceBlock(); timer.observe(false)
        advanceTimeBy(10_000)
        sleepElapsed = 120_000
        timer.observe(true); timer.enforceBlock()
        assertFalse(timer.isBlocked); assertEquals(1, host.exits)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(0, host.blocks)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.blocks)
        timer.pause()
    }
}
