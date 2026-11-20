package com.local.personaltoolbox.accessibility.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal interface AdSkipHost {
    fun isAllowed(): Boolean
    fun currentTarget(): AdSkipTarget?
    suspend fun tap(target: AdSkipTarget, point: TapPoint): Boolean
    fun onSkipped()
}

/** One cancellable attempt per visible button; does not retain AccessibilityNodeInfo objects. */
internal class AdSkipController(
    private val scope: CoroutineScope,
    private val host: AdSkipHost,
    private val nextDelay: () -> Long,
    private val nextPoint: (TapBounds) -> TapPoint,
) {
    private var observed: AdSkipTarget? = null
    private var attempted: AdSkipTarget? = null
    private var pending: Job? = null
    private var inFlight = false

    fun observe(target: AdSkipTarget?) {
        if (!inFlight && observed != target) { pending?.cancel(); pending = null }
        if (target != attempted) attempted = null
        observed = target
    }

    fun canSchedule() = observed != null && pending == null && observed != attempted

    fun schedule() {
        if (!canSchedule()) return
        val target = observed ?: return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(nextDelay().coerceIn(0, 1_000))
                if (!host.isAllowed() || host.currentTarget() != target) return@launch
                val point = nextPoint(target.bounds)
                if (!target.bounds.contains(point)) return@launch
                attempted = target
                inFlight = true
                val completed = withTimeoutOrNull(1_500) { host.tap(target, point) } == true
                if (!completed) return@launch
                delay(150) // Allow the app to update its tree before verifying that the control disappeared.
                if (host.isAllowed() && host.currentTarget() != target) host.onSkipped()
            } catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) { /* Stale/invalid accessibility state must not trigger a click retry loop. */ }
            finally {
                if (pending === coroutineContext[Job]) { pending = null; inFlight = false }
            }
        }
        pending = task
        task.start()
    }

    fun onUserInteraction() {
        if (!inFlight) { pending?.cancel(); pending = null }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        inFlight = false
        observed = null
        attempted = null
    }
}
