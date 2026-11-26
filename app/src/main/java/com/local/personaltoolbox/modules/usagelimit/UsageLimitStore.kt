package com.local.personaltoolbox.modules.usagelimit

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.local.personaltoolbox.data.ToolboxPreferences
import kotlinx.coroutines.flow.first

/** Additive optional keys. Missing keys in older installations mean no active block. */
internal class UsageLimitStore(private val preferences: ToolboxPreferences, id: String) {
    private val started = longPreferencesKey("${id}_block_started_at")
    private val until = longPreferencesKey("${id}_blocked_until")

    suspend fun remaining(nowMillis: Long): Long {
        val saved = preferences.store.data.first()
        val start = saved[started] ?: return 0
        val end = saved[until] ?: return 0
        // A backwards clock change must not turn a one-minute block into a long lockout.
        return if (nowMillis >= start && end > nowMillis && end - nowMillis <= BLOCK_MILLIS)
            end - nowMillis else 0
    }

    suspend fun start(nowMillis: Long) {
        preferences.store.edit {
            it[started] = nowMillis
            it[until] = nowMillis + BLOCK_MILLIS
        }
    }

    companion object { const val BLOCK_MILLIS = 60_000L }
}
