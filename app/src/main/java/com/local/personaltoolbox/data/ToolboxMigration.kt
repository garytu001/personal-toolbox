package com.local.personaltoolbox.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/** Keep previously installed Personal Toolbox settings when adopting stable module IDs. */
internal object ToolboxMigration : DataMigration<Preferences> {
    private val legacyPrefixes = mapOf("youtube_shorts" to "yt_shorts", "instagram_reels" to "ig_reels")
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyPrefixes.any { (old, new) ->
        (currentData[booleanPreferencesKey("${new}_enabled")] == null && currentData[booleanPreferencesKey("${old}_enabled")] != null) ||
            (currentData[intPreferencesKey("${new}_blocked_count")] == null && currentData[intPreferencesKey("${old}_blocked_count")] != null)
    }
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        for ((old, new) in legacyPrefixes) {
            val enabledKey = booleanPreferencesKey("${new}_enabled")
            val countKey = intPreferencesKey("${new}_blocked_count")
            if (this[enabledKey] == null) currentData[booleanPreferencesKey("${old}_enabled")]?.let { this[enabledKey] = it }
            if (this[countKey] == null) currentData[intPreferencesKey("${old}_blocked_count")]?.let { this[countKey] = it }
        }
    }
    override suspend fun cleanUp() = Unit
}
