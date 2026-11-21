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
    private val exit: () -> Boolean,
    private val onExited: () -> Unit,
    private val cooldownMillis: Long,
) {
    private var inForeground = false
    private var deadline: Job? = null
    private var lastAttempt: Long? = null

    init { require(limitMillis > 0); require(cooldownMillis >= 0) }

    fun observe(isTargetForeground: Boolean) {
        if (!isTargetForeground) { cancel(); return }
        if (inForeground) return
        inForeground = true
        val startedAt = now()
        deadline = scope.launch {
            // Use the monotonic start time even if dispatching this coroutine was delayed.
            delay((limitMillis - (now() - startedAt)).coerceAtLeast(0))
            if (!isAllowed()) return@launch
            val time = now()
            if (lastAttempt?.let { time - it < cooldownMillis } == true) return@launch
            // Never repeat a failed HOME request or start again on content events in this visit.
            lastAttempt = time
            if (exit()) onExited()
        }
    }

    fun cancel() {
        deadline?.cancel()
        deadline = null
        inForeground = false
    }
}
