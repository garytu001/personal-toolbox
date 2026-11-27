package com.local.personaltoolbox.modules.usagelimit

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.local.personaltoolbox.data.ToolboxPreferences
import kotlinx.coroutines.flow.first

/** Additive optional keys. Missing keys in older installations mean no active block. */
internal class UsageLimitStore(private val preferences: ToolboxPreferences) {

    suspend fun remaining(nowMillis: Long): Long {
        val saved = preferences.store.data.first()
        val start = saved[STARTED] ?: return 0
        val end = saved[UNTIL] ?: return 0
        // A backwards clock change must not turn a one-minute block into a long lockout.
        return if (nowMillis >= start && end > nowMillis && end - nowMillis <= BLOCK_MILLIS)
            end - nowMillis else 0
    }

    suspend fun start(nowMillis: Long, remainingMillis: Long = BLOCK_MILLIS) {
        preferences.store.edit {
            it[STARTED] = nowMillis
            it[UNTIL] = nowMillis + remainingMillis.coerceIn(0, BLOCK_MILLIS)
        }
    }

    companion object {
        const val BLOCK_MILLIS = 60_000L
        val STARTED = longPreferencesKey("app_usage_limit_block_started_at")
        val UNTIL = longPreferencesKey("app_usage_limit_blocked_until")
    }
}
