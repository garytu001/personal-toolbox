package com.local.personaltoolbox.modules.usagelimit

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey

/** Retire the five published per-app deadlines, preserving the latest valid remaining break. */
internal class UsageBlockMigration(private val now: () -> Long = System::currentTimeMillis) : DataMigration<Preferences> {
    private val legacyIds = listOf("yt_usage_limit", "ig_usage_limit", "threads_usage_limit", "fb_usage_limit", "dcard_usage_limit")
    private val legacyKeys = legacyIds.flatMap { listOf(startKey(it), endKey(it)) }
    override suspend fun shouldMigrate(currentData: Preferences) = legacyKeys.any { currentData[it] != null }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        if (this[UsageLimitStore.STARTED] == null && this[UsageLimitStore.UNTIL] == null) {
            val time = now()
            val latest = legacyIds.mapNotNull { id ->
                val start = currentData[startKey(id)] ?: return@mapNotNull null
                val end = currentData[endKey(id)] ?: return@mapNotNull null
                if (start <= time && end > time && end - time <= UsageLimitStore.BLOCK_MILLIS &&
                    end - start == UsageLimitStore.BLOCK_MILLIS) start to end else null
            }.maxByOrNull { it.second }
            if (latest != null) {
                this[UsageLimitStore.STARTED] = latest.first
                this[UsageLimitStore.UNTIL] = latest.second
            }
        }
        legacyKeys.forEach { remove(it) }
    }

    override suspend fun cleanUp() = Unit
    private fun startKey(id: String) = longPreferencesKey("${id}_block_started_at")
    private fun endKey(id: String) = longPreferencesKey("${id}_blocked_until")
}
