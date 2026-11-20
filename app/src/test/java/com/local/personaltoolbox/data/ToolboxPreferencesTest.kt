package com.local.personaltoolbox.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.local.personaltoolbox.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolboxPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val definitions = listOf(ModuleDefinition("yt_shorts", 1), ModuleDefinition("ig_reels", 2))

    @Test fun persistsOrderSwitchAndCountAcrossStoreRecreation() = runBlocking {
        val file = temporary.newFile("restart.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val preferences = ToolboxPreferences(firstStore, firstScope, definitions)
        preferences.setEnabled("yt_shorts", false)
        preferences.incrementBlockedCount("ig_reels")
        preferences.setModuleOrder(listOf("ig_reels", "yt_shorts"))
        firstScope.cancel()
        firstScope.coroutineContext[kotlinx.coroutines.Job]?.join()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
            val restored = ToolboxPreferences(secondStore, secondScope, definitions + ModuleDefinition("timer", 3))
            val settings = withTimeout(5_000) { restored.settings.filterNotNull().first() }
            assertFalse(settings.isEnabled("yt_shorts"))
            assertEquals(1, settings.blockedCount("ig_reels"))
            assertEquals(listOf("ig_reels", "yt_shorts", "timer"), settings.moduleOrder)
            assertTrue(settings.isEnabled("timer"))
        } finally { secondScope.cancel() }
    }

    @Test fun migratesLegacyKeysAndDoesNotOverwriteExistingNewKeys() = runBlocking {
        val file = temporary.newFile("migration.preferences_pb")
        val initialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initialStore = PreferenceDataStoreFactory.create(scope = initialScope) { file }
        initialStore.edit {
            it[booleanPreferencesKey("youtube_shorts_enabled")] = false
            it[intPreferencesKey("youtube_shorts_blocked_count")] = 42
            it[intPreferencesKey("instagram_reels_blocked_count")] = 15
            it[intPreferencesKey("ig_reels_blocked_count")] = 70
        }
        initialScope.cancel()
        initialScope.coroutineContext[kotlinx.coroutines.Job]?.join()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(migrations = listOf(ToolboxMigration), scope = scope) { file }
            val settings = withTimeout(5_000) { ToolboxPreferences(store, scope, definitions).settings.filterNotNull().first() }
            assertFalse(settings.isEnabled("yt_shorts"))
            assertEquals(42, settings.blockedCount("yt_shorts"))
            assertEquals(70, settings.blockedCount("ig_reels"))
        } finally { scope.cancel() }
    }

    @Test fun concurrentIncrementsDoNotLoseCountsAndSaturateAtIntMax() = runBlocking {
        val file = temporary.newFile("counter.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val preferences = ToolboxPreferences(store, scope, definitions)
            (1..30).map { async(Dispatchers.Default) { preferences.incrementBlockedCount("yt_shorts") } }.awaitAll()
            val key = intPreferencesKey("yt_shorts_blocked_count")
            assertEquals(30, store.data.first()[key])
            store.edit { it[key] = Int.MAX_VALUE }
            preferences.incrementBlockedCount("yt_shorts")
            assertEquals(Int.MAX_VALUE, store.data.first()[key])
        } finally { scope.cancel() }
    }
}
