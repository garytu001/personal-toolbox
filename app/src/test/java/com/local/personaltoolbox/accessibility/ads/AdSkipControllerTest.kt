@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.local.personaltoolbox.accessibility.ads

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AdSkipControllerTest {
    private val target = AdSkipTarget(10, "skip", TapBounds(100, 100, 300, 200))
    private class Host(var target: AdSkipTarget?) : AdSkipHost {
        var allowed = true
        var taps = 0
        var skips = 0
        var completes = true
        var disappear = true
        var failLookup = false
        var tapDelay = 0L
        override fun isAllowed() = allowed
        override fun currentTarget(): AdSkipTarget? {
            if (failLookup) throw IllegalStateException("stale tree")
            return target
        }
        override suspend fun tap(target: AdSkipTarget, point: TapPoint): Boolean {
            taps++
            if (tapDelay > 0) delay(tapDelay)
            if (completes && disappear) this.target = null
            return completes
        }
        override fun onSkipped() { skips++ }
    }
    private fun controller(scope: kotlinx.coroutines.CoroutineScope, host: Host) =
        AdSkipController(scope, host, { 500 }, { TapPoint(200f, 150f) })

    @Test fun waitsThenRevalidatesAndCountsOnlyAfterDisappearance() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent()
        advanceTimeBy(499); runCurrent(); assertEquals(0, host.taps)
        advanceTimeBy(1); runCurrent(); assertEquals(1, host.taps); assertEquals(0, host.skips)
        advanceUntilIdle(); assertEquals(1, host.skips)
    }
    @Test fun repeatedContentEventsDoNotQueueDuplicateTaps() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        repeat(20) { controller.observe(target); controller.schedule() }
        advanceUntilIdle()
        assertEquals(1, host.taps); assertEquals(1, host.skips)
    }
    @Test fun disablingOrLeavingYoutubePreventsThePendingTap() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent()
        host.allowed = false
        advanceUntilIdle()
        assertEquals(0, host.taps); assertEquals(0, host.skips)
    }
    @Test fun movedOrReplacedButtonIsNotClickedUsingOldCoordinates() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent()
        host.target = target.copy(bounds = TapBounds(400, 100, 600, 200))
        advanceUntilIdle()
        assertEquals(0, host.taps)
    }
    @Test fun cancellationOrUserInteractionStopsTheDelay() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent(); controller.onUserInteraction()
        advanceUntilIdle(); assertEquals(0, host.taps)
        controller.observe(target); controller.schedule(); runCurrent(); controller.cancel()
        advanceUntilIdle(); assertEquals(0, host.taps)
    }
    @Test fun buttonDisappearanceDuringWaitCancelsTheAttempt() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent()
        controller.observe(null)
        advanceUntilIdle(); assertEquals(0, host.taps)
    }
    @Test fun cancelledOrUnsuccessfulGestureNeverIncrementsCount() = runTest {
        val host = Host(target).apply { completes = false }
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(1, host.taps); assertEquals(0, host.skips)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(1, host.taps)
    }
    @Test fun gestureWithoutControlDisappearanceDoesNotCountAsSkip() = runTest {
        val host = Host(target).apply { disappear = false }
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(0, host.skips)
    }
    @Test fun aNewButtonCanBeSkippedAfterTheOldOneDisappears() = runTest {
        val host = Host(target)
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        controller.observe(null)
        host.target = target
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(2, host.taps); assertEquals(2, host.skips)
    }
    @Test fun ownClickEventDoesNotCancelInFlightVerification() = runTest {
        val host = Host(target).apply { tapDelay = 40 }
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); runCurrent()
        advanceTimeBy(500); runCurrent(); controller.onUserInteraction(); controller.observe(null)
        advanceUntilIdle(); assertEquals(1, host.skips)
    }
    @Test fun lostGestureCallbackTimesOutWithoutCountingOrRetrying() = runTest {
        val host = Host(target).apply { tapDelay = 10_000 }
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(1, host.taps); assertEquals(0, host.skips)
    }
    @Test fun staleAccessibilityTreeCannotCrashTheControllerOrCount() = runTest {
        val host = Host(target).apply { failLookup = true }
        val controller = controller(this, host)
        controller.observe(target); controller.schedule(); advanceUntilIdle()
        assertEquals(0, host.taps); assertEquals(0, host.skips)
    }
}
