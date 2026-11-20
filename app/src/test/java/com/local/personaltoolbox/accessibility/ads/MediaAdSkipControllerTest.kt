package com.local.personaltoolbox.accessibility.ads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaAdSkipControllerTest {
    private class Host : MediaAdSkipHost {
        var allowed = true
        var ad: AdPlaybackTarget? = AdPlaybackTarget(4)
        var available = true
        var nextAccepted = true
        var previousAccepted = true
        var original = true
        var nextCalls = 0
        var previousCalls = 0
        var successes = 0
        val changed = CompletableDeferred<Boolean>()
        val returned = CompletableDeferred<Boolean>()
        val bookmark = VideoBookmark("youtube-session", "original-video")
        override fun isAllowed() = allowed
        override fun currentAd() = ad
        override fun prepare() = if (available) bookmark else null
        override fun next(bookmark: VideoBookmark): Boolean { nextCalls++; return nextAccepted }
        override suspend fun awaitDifferentVideo(bookmark: VideoBookmark) = changed.await()
        override fun previous(bookmark: VideoBookmark): Boolean { previousCalls++; return previousAccepted }
        override suspend fun awaitOriginalVideo(bookmark: VideoBookmark) = returned.await()
        override fun isOriginalVideo(bookmark: VideoBookmark) = original
        override fun onSkipped() { successes++ }
    }

    @Test fun waitsForNextMetadataBeforeReturningAndCountsOnlyOriginalWithoutAd() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 50 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule()
        advanceTimeBy(49); runCurrent(); assertEquals(0, host.nextCalls)
        advanceTimeBy(1); runCurrent(); assertEquals(1, host.nextCalls)
        advanceTimeBy(100); runCurrent(); assertEquals(0, host.previousCalls)
        host.changed.complete(true); runCurrent()
        advanceTimeBy(50); runCurrent(); assertEquals(1, host.previousCalls)
        host.ad = null; host.returned.complete(true); runCurrent()
        advanceTimeBy(599); runCurrent(); assertEquals(0, host.successes)
        advanceTimeBy(1); runCurrent(); assertEquals(1, host.successes)
    }
    @Test fun repeatedContentEventsDoNotDuplicateAnAttempt() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad)
        repeat(20) { controller.schedule(); runCurrent(); controller.observe(host.ad) }
        assertEquals(1, host.nextCalls)
        controller.cancel()
    }
    @Test fun notYetReadySessionSendsNothingAndMayBeRetriedWhenReady() = runTest {
        val host = Host().apply { available = false }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        assertEquals(0, host.nextCalls)
        host.available = true; controller.schedule(); runCurrent()
        assertEquals(1, host.nextCalls)
        assertFalse(controller.canSchedule())
        controller.cancel()
    }
    @Test fun leavingOrDisablingDuringInitialDelayPreventsBothCommands() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 50 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); host.allowed = false
        advanceTimeBy(100); runCurrent()
        assertEquals(0, host.nextCalls); assertEquals(0, host.previousCalls)
    }
    @Test fun adDisappearingDuringDelayCancelsTheAttempt() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 50 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule()
        host.ad = null; controller.observe(null)
        advanceTimeBy(100); runCurrent(); assertEquals(0, host.nextCalls)
    }
    @Test fun nextFailureNeverSendsPreviousOrCounts() = runTest {
        val host = Host().apply { nextAccepted = false }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        assertEquals(1, host.nextCalls); assertEquals(0, host.previousCalls); assertEquals(0, host.successes)
        assertFalse(controller.canSchedule())
    }
    @Test fun nextTimeoutDoesNotBlindlyReturnToUnrelatedVideo() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(0, host.previousCalls); assertEquals(0, host.successes)
    }
    @Test fun originalVideoWithAdStillVisibleIsNotCounted() = runTest {
        val host = Host().apply { changed.complete(true); returned.complete(true) }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        advanceTimeBy(601); runCurrent()
        assertEquals(1, host.previousCalls); assertEquals(0, host.successes)
    }
    @Test fun wrongVideoEvenWithoutAdIsNotCounted() = runTest {
        val host = Host().apply { changed.complete(true); returned.complete(true); original = false }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        host.ad = null; advanceTimeBy(601); runCurrent()
        assertEquals(0, host.successes)
    }
    @Test fun manualClickCancelsReturnTrip() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        controller.onUserInteraction(scrolled = false)
        host.changed.complete(true); advanceTimeBy(100); runCurrent()
        assertEquals(0, host.previousCalls)
    }
    @Test fun ownTrackScrollDoesNotCancelReturnTrip() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        controller.onUserInteraction(scrolled = true)
        host.changed.complete(true); runCurrent()
        assertEquals(1, host.previousCalls)
        controller.cancel()
    }
    @Test fun disableCancelsCallbackWait() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        controller.cancel(); host.changed.complete(true); runCurrent()
        assertEquals(0, host.previousCalls)
    }
    @Test fun shortForegroundChangesCannotCreateRapidSkipLoops() = runTest {
        val host = Host().apply { nextAccepted = false }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        controller.cancel(); controller.observe(host.ad); controller.schedule(); runCurrent()
        assertEquals(1, host.nextCalls)
        advanceTimeBy(10_001); controller.schedule(); runCurrent()
        assertEquals(2, host.nextCalls)
    }
    @Test fun accessRevokedAfterNextDoesNotSendPrevious() = runTest {
        val host = Host()
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        host.allowed = false; host.changed.complete(true); runCurrent()
        assertEquals(0, host.previousCalls)
    }

    @Test fun rejectedPreviousDoesNotCountOrLoop() = runTest {
        val host = Host().apply { changed.complete(true); previousAccepted = false }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        assertEquals(1, host.previousCalls); assertEquals(0, host.successes)
        assertFalse(controller.canSchedule())
    }
    @Test fun returnTimeoutNeverCountsNextAsAnAdSkip() = runTest {
        val host = Host().apply { changed.complete(true) }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(1, host.previousCalls); assertEquals(0, host.successes)
    }
    @Test fun newAdCanRearmAfterSuccessfulReturnAndBackoff() = runTest {
        val host = Host().apply { changed.complete(true); returned.complete(true) }
        val controller = MediaAdSkipController(this, host, { 0 }, { testScheduler.currentTime })
        controller.observe(host.ad); controller.schedule(); runCurrent()
        host.ad = null; controller.observe(null)
        advanceTimeBy(601); runCurrent(); assertEquals(1, host.successes)
        host.ad = AdPlaybackTarget(4); controller.observe(host.ad)
        assertFalse(controller.canSchedule())
        advanceTimeBy(10_000); runCurrent(); assertTrue(controller.canSchedule())
    }
}
