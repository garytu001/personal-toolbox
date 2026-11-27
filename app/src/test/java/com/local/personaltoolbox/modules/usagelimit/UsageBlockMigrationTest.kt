package com.local.personaltoolbox.modules.usagelimit

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsageBlockMigrationTest {
    @Test fun unifiesLatestActiveBreakAndPreservesAllSwitchesCountsAndOrder() = runBlocking {
        val prior = mutablePreferencesOf(
            longPreferencesKey("yt_usage_limit_block_started_at") to 80_000L,
            longPreferencesKey("yt_usage_limit_blocked_until") to 140_000L,
            longPreferencesKey("ig_usage_limit_block_started_at") to 90_000L,
            longPreferencesKey("ig_usage_limit_blocked_until") to 150_000L,
            booleanPreferencesKey("yt_usage_limit_enabled") to false,
            intPreferencesKey("ig_usage_limit_blocked_count") to 42,
            stringPreferencesKey("module_order") to "ig_usage_limit,yt_usage_limit",
        )
        val migration = UsageBlockMigration { 100_000 }
        assertTrue(migration.shouldMigrate(prior))
        val result = migration.migrate(prior)
        assertEquals(90_000L, result[UsageLimitStore.STARTED])
        assertEquals(150_000L, result[UsageLimitStore.UNTIL])
        assertEquals(false, result[booleanPreferencesKey("yt_usage_limit_enabled")])
        assertEquals(42, result[intPreferencesKey("ig_usage_limit_blocked_count")])
        assertEquals("ig_usage_limit,yt_usage_limit", result[stringPreferencesKey("module_order")])
        assertNull(result[longPreferencesKey("ig_usage_limit_blocked_until")])
        assertFalse(migration.shouldMigrate(result))
        assertEquals(result, migration.migrate(result))
    }

    @Test fun expiredFutureAndMalformedLegacyBreaksAreNotRevived() = runBlocking {
        for ((start, end) in listOf(0L to 60_000L, 110_000L to 170_000L, 90_000L to 900_000L)) {
            val result = UsageBlockMigration { 100_000 }.migrate(mutablePreferencesOf(
                longPreferencesKey("fb_usage_limit_block_started_at") to start,
                longPreferencesKey("fb_usage_limit_blocked_until") to end))
            assertNull(result[UsageLimitStore.UNTIL])
            assertNull(result[longPreferencesKey("fb_usage_limit_blocked_until")])
        }
    }

    @Test fun existingSharedDeadlineIsNeverOverwrittenByLegacyValues() = runBlocking {
        val result = UsageBlockMigration { 100_000 }.migrate(mutablePreferencesOf(
            UsageLimitStore.STARTED to 70_000L, UsageLimitStore.UNTIL to 130_000L,
            longPreferencesKey("dcard_usage_limit_block_started_at") to 90_000L,
            longPreferencesKey("dcard_usage_limit_blocked_until") to 150_000L))
        assertEquals(130_000L, result[UsageLimitStore.UNTIL])
    }
}
