package com.local.personaltoolbox.accessibility.ads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class AdPlaybackTarget(val windowId: Int)
internal data class VideoBookmark(val sessionId: String, val videoId: String)

internal interface MediaAdSkipHost {
    fun isAllowed(): Boolean
    fun currentAd(): AdPlaybackTarget?
    fun prepare(): VideoBookmark?
    fun next(bookmark: VideoBookmark): Boolean
    suspend fun awaitDifferentVideo(bookmark: VideoBookmark): Boolean
    fun previous(bookmark: VideoBookmark): Boolean
    suspend fun awaitOriginalVideo(bookmark: VideoBookmark): Boolean
    fun isOriginalVideo(bookmark: VideoBookmark): Boolean
    fun onSkipped()
}

/** One bounded next/previous transaction per ad episode. No UI coordinates or background polling. */
internal class MediaAdSkipController(
    private val scope: CoroutineScope,
    private val host: MediaAdSkipHost,
    private val nextDelay: () -> Long,
    private val now: () -> Long,
) {
    private var observed: AdPlaybackTarget? = null
    private var attemptedInEpisode = false
    private var lastAttempt: Long? = null
    private var pending: Job? = null
    private var inFlight = false

    fun observe(target: AdPlaybackTarget?) {
        if (!inFlight && observed != target) { pending?.cancel(); pending = null }
        if (target == null && !inFlight) attemptedInEpisode = false
        observed = target
    }

    fun canSchedule(): Boolean = observed != null && pending == null && !attemptedInEpisode &&
        (lastAttempt?.let { now() - it >= 10_000 } != false)

    fun schedule() {
        if (!canSchedule()) return
        val target = observed ?: return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(nextDelay().coerceIn(0, 100))
                if (!host.isAllowed() || host.currentAd() != target) return@launch
                val bookmark = host.prepare() ?: return@launch
                // A not-yet-ready session may be checked on a later UI event; commands are sent only once.
                attemptedInEpisode = true
                lastAttempt = now()
                inFlight = true
                if (!host.isAllowed() || host.currentAd() != target || !host.next(bookmark)) return@launch
                if (withTimeoutOrNull(3_000) { host.awaitDifferentVideo(bookmark) } != true) return@launch
                delay(nextDelay().coerceIn(0, 100))
                if (!host.isAllowed() || !host.previous(bookmark)) return@launch
                if (withTimeoutOrNull(3_000) { host.awaitOriginalVideo(bookmark) } != true) return@launch
                delay(600) // A metadata return may precede the ad UI. Never count the NEXT alone as success.
                if (host.isAllowed() && host.isOriginalVideo(bookmark) && host.currentAd() == null) host.onSkipped()
            } catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) { /* Revoked access or stale sessions must fail closed. */ }
            finally {
                if (pending === coroutineContext[Job]) {
                    pending = null
                    inFlight = false
                    if (observed == null) attemptedInEpisode = false
                }
            }
        }
        pending = task
        task.start()
    }

    fun onUserInteraction(scrolled: Boolean) {
        // Track switching can produce a synthetic scroll; it must not break our own return trip.
        if (scrolled && inFlight) return
        pending?.cancel()
        pending = null
        inFlight = false
        attemptedInEpisode = true
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        inFlight = false
        observed = null
        attemptedInEpisode = false
        // Keep the backoff across disable/re-enable or transient foreground changes.
    }
}
