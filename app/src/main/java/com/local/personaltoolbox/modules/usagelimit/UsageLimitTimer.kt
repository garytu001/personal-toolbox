package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** One continuous-use deadline, one block expiry, and at most one event-triggered HOME retry.
 * Leaving resets only the usage visit. It never clears or extends the one-minute block.
 */
internal class UsageLimitTimer(
    private val scope: CoroutineScope,
    private val limitMillis: Long,
    private val now: () -> Long,
    private val isAllowed: () -> Boolean,
    private val goHome: () -> Boolean,
    private val onExited: () -> Unit,
    private val onBlockStarted: () -> Unit,
    private val blockMillis: Long = 60_000,
    initialBlockMillis: Long = 0,
    private val cooldownMillis: Long = 450,
) {
    private var foreground = false
    private var visit: Job? = null
    private var expiry: Job? = null
    private var retry: Job? = null
    private var blockedUntil = 0L
    private var lastAttempt: Long? = null
    val isBlocked: Boolean get() = now() < blockedUntil

    init {
        require(limitMillis > 0 && blockMillis > 0 && cooldownMillis >= 0)
        if (initialBlockMillis > 0) {
            blockedUntil = now() + initialBlockMillis.coerceAtMost(blockMillis)
            scheduleExpiry()
        }
    }

    fun observe(isTargetForeground: Boolean) {
        // Android's main-thread delay can resume late after deep sleep. A fresh event must
        // expire the monotonic deadline now, instead of letting its stale job reset a new visit.
        if (blockedUntil > 0 && !isBlocked) {
            expiry?.cancel()
            expiry = null
            retry?.cancel()
            retry = null
            blockedUntil = 0
            foreground = false
        }
        if (!isTargetForeground || !scope.isActive) { pause(); return }
        if (foreground) return
        foreground = true
        if (!isBlocked) startVisit()
    }

    /** Called only for a validated foreground event, or a fresh foreground check on reconnect. */
    fun enforceBlock() {
        if (!scope.isActive || !foreground || !isBlocked || !isAllowed()) return
        val remainingCooldown = lastAttempt?.let { cooldownMillis - (now() - it) } ?: 0
        if (remainingCooldown > 0) {
            if (retry?.isActive != true) retry = scope.launch {
                delay(remainingCooldown)
                retry = null
                // Single deferred response to an event, not periodic enforcement/polling.
                enforceBlock()
            }
            return
        }
        lastAttempt = now()
        if (goHome()) onExited()
    }

    private fun startVisit() {
        visit?.cancel()
        val deadline = now() + limitMillis
        visit = scope.launch {
            delay((deadline - now()).coerceAtLeast(0))
            visit = null
            if (!foreground || !isAllowed()) return@launch
            blockedUntil = now() + blockMillis
            scheduleExpiry()
            onBlockStarted()
            enforceBlock()
        }
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        expiry = scope.launch {
            delay((blockedUntil - now()).coerceAtLeast(0))
            expiry = null
            retry?.cancel()
            retry = null
            blockedUntil = 0
            // If HOME failed and the app stayed open, a fresh full visit starts at expiry.
            if (foreground && isAllowed()) startVisit()
        }
    }

    fun pause() {
        foreground = false
        visit?.cancel()
        visit = null
        retry?.cancel()
        retry = null
    }
}
