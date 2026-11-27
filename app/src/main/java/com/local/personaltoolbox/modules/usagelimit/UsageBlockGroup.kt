package com.local.personaltoolbox.modules.usagelimit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread shared deadline. No UI actions, service references, or polling in this object. */
internal class UsageBlockGroup(
    private val now: () -> Long,
    private val loadRemaining: suspend () -> Long,
    private val persist: (remainingMillis: Long) -> Unit,
) {
    private val initialization = Mutex()
    private var initialized = false
    private val mutableUntil = MutableStateFlow(0L)
    val until = mutableUntil.asStateFlow()

    suspend fun awaitReady() = initialization.withLock {
        if (!initialized) {
            val readAt = now()
            val remaining = loadRemaining().coerceIn(0, UsageLimitStore.BLOCK_MILLIS)
            val adjusted = (remaining - (now() - readAt)).coerceAtLeast(0)
            mutableUntil.value = if (adjusted > 0) now() + adjusted else 0
            initialized = true
        }
    }

    /** An overlapping deadline/reentry joins the existing break, never extends it. */
    fun start(requestedUntil: Long): Long {
        check(initialized)
        val time = now()
        if (mutableUntil.value > time) return mutableUntil.value
        val end = requestedUntil.coerceAtMost(time + UsageLimitStore.BLOCK_MILLIS)
        if (end <= time) return 0
        mutableUntil.value = end
        persist(end - time)
        return end
    }
}
