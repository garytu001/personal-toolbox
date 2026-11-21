package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuousUseTimerTest {
    private class Host {
        var allowed = true
        var accepted = true
        var exits = 0
        var successes = 0
        fun timer(scope: CoroutineScope, now: () -> Long, minutes: Int = 5) = ContinuousUseTimer(
            scope, minutes * 60_000L, now, { allowed }, { exits++; accepted }, { successes++ }, 450L)
    }

    @Test fun ownRemovalForegroundChangeDoesNotCancelItsSuccessReceipt() = runTest {
        val receipt = CompletableDeferred<Boolean>()
        var count = 0
        val timer = ContinuousUseTimer(this, 300_000, { testScheduler.currentTime }, { true }, { receipt.await() }, { count++ }, 450)
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        timer.observe(false)
        receipt.complete(true); runCurrent()
        assertEquals(1, count)
    }

    @Test fun newVisitDuringReceiptWaitGetsItsOwnDeadline() = runTest {
        val receipt = CompletableDeferred<Boolean>()
        var calls = 0
        var count = 0
        val timer = ContinuousUseTimer(this, 300_000, { testScheduler.currentTime }, { true }, {
            calls++
            if (calls == 1) receipt.await() else true
        }, { count++ }, 450)
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        timer.observe(false)
        advanceTimeBy(1_000)
        timer.observe(true)
        advanceTimeBy(1_000)
        receipt.complete(true); runCurrent()
        assertEquals(1, count)
        advanceTimeBy(298_999); runCurrent()
        assertEquals(1, count)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, count)
    }

    @Test fun explicitDisableCancelsAnInFlightReceiptWithoutRestartingAVisit() = runTest {
        val receipt = CompletableDeferred<Boolean>()
        var count = 0
        val timer = ContinuousUseTimer(this, 300_000, { testScheduler.currentTime }, { true }, { receipt.await() }, { count++ }, 450)
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        timer.observe(false); timer.observe(true)
        timer.cancel()
        receipt.complete(true)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, count)
    }

    @Test fun exitsAtTwentyMinutesWithoutAnyFurtherEvents() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime }, 20)
        timer.observe(true)
        advanceTimeBy(20 * 60_000L - 1); runCurrent()
        assertEquals(0, host.exits)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.exits)
        assertEquals(1, host.successes)
    }

    @Test fun repeatedEventsAndInteractionsDoNotExtendFiveMinuteVisitOrRepeatExit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        repeat(4) { advanceTimeBy(60_000); timer.observe(true) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, host.exits)
        repeat(10) { timer.observe(true) }
        advanceTimeBy(600_000); runCurrent()
        assertEquals(1, host.exits)
    }

    @Test fun leavingForAnotherAppThenReturningStartsFullNewVisit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(240_000)
        timer.observe(false)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, host.exits)
        timer.observe(true)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(0, host.exits)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.exits)
    }

    @Test fun lockingScreenOrDisablingOrInterruptingCancelsPendingExit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(299_999)
        timer.cancel()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, host.exits)
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, host.exits)
    }

    @Test fun lastMomentForegroundOrSettingsCheckPreventsUnrelatedAppExit() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(299_999)
        host.allowed = false
        advanceTimeBy(1); runCurrent()
        assertEquals(0, host.exits)
        assertEquals(0, host.successes)
    }

    @Test fun failedHomeIsNotCountedOrRepeatedUntilANewVisit() = runTest {
        val host = Host().apply { accepted = false }
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, host.exits)
        assertEquals(0, host.successes)
        repeat(5) { timer.observe(true); advanceTimeBy(300_000); runCurrent() }
        assertEquals(1, host.exits)
        timer.observe(false)
        host.accepted = true
        timer.observe(true)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, host.exits)
        assertEquals(1, host.successes)
    }

    @Test fun eachAppHasItsOwnDeadlineAndLeavingOneDoesNotCountAgainstAnother() = runTest {
        val youtubeHost = Host()
        val instagramHost = Host()
        val youtube = youtubeHost.timer(this, { testScheduler.currentTime }, 20)
        val instagram = instagramHost.timer(this, { testScheduler.currentTime })
        youtube.observe(true)
        advanceTimeBy(10 * 60_000)
        youtube.observe(false)
        instagram.observe(true)
        advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals(0, youtubeHost.exits)
        assertEquals(1, instagramHost.exits)
    }

    @Test fun destroyingServiceScopeCancelsDeadline() = runTest {
        val host = Host()
        val serviceScope = CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val timer = host.timer(serviceScope, { testScheduler.currentTime })
        timer.observe(true)
        runCurrent()
        serviceScope.cancel()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(0, host.exits)
    }

    @Test fun foregroundChangeAtExactDeadlineCancelsBeforeHomeIsDispatched() = runTest {
        val host = Host()
        val timer = host.timer(this, { testScheduler.currentTime })
        timer.observe(true)
        advanceTimeBy(300_000)
        timer.observe(false)
        runCurrent()
        assertEquals(0, host.exits)
    }

    @Test fun eachInstanceEnforcesItsOwnCooldownEvenAcrossNewVisits() = runTest {
        var firstExits = 0
        var secondExits = 0
        val first = ContinuousUseTimer(this, 10, { testScheduler.currentTime }, { true }, { firstExits++; true }, {}, 450)
        val second = ContinuousUseTimer(this, 10, { testScheduler.currentTime }, { true }, { secondExits++; true }, {}, 450)
        first.observe(true)
        advanceTimeBy(10); runCurrent()
        first.observe(false); first.observe(true)
        second.observe(true)
        advanceTimeBy(10); runCurrent()
        assertEquals(1, firstExits)
        assertEquals(1, secondExits)
        first.observe(false)
        advanceTimeBy(450)
        first.observe(true)
        advanceTimeBy(10); runCurrent()
        assertEquals(2, firstExits)
    }
}
