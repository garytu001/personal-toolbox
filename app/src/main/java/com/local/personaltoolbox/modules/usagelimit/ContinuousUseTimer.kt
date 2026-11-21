package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One deadline per foreground visit; taps and content updates do not extend the visit. */
internal class ContinuousUseTimer(
    private val scope: CoroutineScope,
    private val limitMillis: Long,
    private val now: () -> Long,
    private val isAllowed: () -> Boolean,
    private val exit: suspend () -> Boolean,
    private val onExited: () -> Unit,
    private val cooldownMillis: Long,
) {
    private var inForeground = false
    private var deadline: Job? = null
    private var lastAttempt: Long? = null
    private var exiting = false
    private var nextVisitAt: Long? = null
    private var generation = 0L

    init { require(limitMillis > 0); require(cooldownMillis >= 0) }

    fun observe(isTargetForeground: Boolean) {
        // An accepted removal itself changes the foreground before its receipt arrives.
        // Keep that receipt, while remembering any genuinely new visit separately.
        if (exiting) {
            if (!isTargetForeground) { inForeground = false; nextVisitAt = null }
            else if (!inForeground) { inForeground = true; nextVisitAt = now() }
            return
        }
        if (!isTargetForeground) { cancel(); return }
        if (inForeground) return
        startVisit(now())
    }

    private fun startVisit(startedAt: Long) {
        inForeground = true
        val ticket = ++generation
        deadline = scope.launch {
            // Use the monotonic start time even if dispatching this coroutine was delayed.
            delay((limitMillis - (now() - startedAt)).coerceAtLeast(0))
            if (!isAllowed()) return@launch
            val time = now()
            if (lastAttempt?.let { time - it < cooldownMillis } == true) return@launch
            // Never repeat an exit request on content events in the same visit.
            lastAttempt = time
            exiting = true
            try { if (exit()) onExited() }
            finally {
                if (ticket == generation) {
                    exiting = false
                    val pending = nextVisitAt
                    nextVisitAt = null
                    if (pending != null) startVisit(pending)
                }
            }
        }
    }

    fun cancel() {
        generation++
        deadline?.cancel()
        deadline = null
        inForeground = false
        exiting = false
        nextVisitAt = null
    }
}
