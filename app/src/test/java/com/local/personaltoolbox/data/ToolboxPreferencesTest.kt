package com.local.personaltoolbox.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.local.personaltoolbox.modules.ModuleDefinition
import com.local.personaltoolbox.modules.ModuleRegistry
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

    @Test fun backgroundRecentsUpgradeStartsDisabledAndPreservesLimitsAndOrder() = runBlocking {
        val file = temporary.newFile("recents-upgrade.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val prior = ModuleRegistry.modules.filterNot { it.id == "recents_control" }
            val old = ToolboxPreferences(store, scope, prior)
            old.setEnabled("yt_usage_limit", false)
            old.incrementBlockedCount("ig_usage_limit")
            val order = prior.map { it.id }.reversed()
            old.setModuleOrder(order)
            val updated = ToolboxPreferences(store, scope, ModuleRegistry.modules)
            val state = withTimeout(5_000) { updated.settings.filterNotNull().first() }
            assertFalse(state.isEnabled("recents_control"))
            assertEquals(0, state.blockedCount("recents_control"))
            assertFalse(state.isEnabled("yt_usage_limit"))
            assertEquals(1, state.blockedCount("ig_usage_limit"))
            assertEquals(order + "recents_control", state.moduleOrder)
        } finally { scope.cancel() }
    }

    @Test fun addingUsageLimitsPreservesOldDataAndKeepsEachNewSwitchAndCountIndependent() = runBlocking {
        val file = temporary.newFile("usage-limits.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val limitIds = listOf("yt_usage_limit", "ig_usage_limit", "threads_usage_limit", "fb_usage_limit", "dcard_usage_limit")
            val oldDefinitions = ModuleRegistry.modules.filter { it.id !in limitIds }
            val old = ToolboxPreferences(store, scope, oldDefinitions)
            old.setEnabled("yt_shorts", false)
            old.incrementBlockedCount("yt_shorts")
            val oldOrder = oldDefinitions.map { it.id }.reversed()
            old.setModuleOrder(oldOrder)
            val updated = ToolboxPreferences(store, scope, ModuleRegistry.modules)
            val loaded = withTimeout(5_000) { updated.settings.filterNotNull().first() }
            assertEquals(oldOrder + limitIds, loaded.moduleOrder)
            assertFalse(loaded.isEnabled("yt_shorts"))
            assertEquals(1, loaded.blockedCount("yt_shorts"))
            limitIds.forEach { assertTrue(loaded.isEnabled(it)); assertEquals(0, loaded.blockedCount(it)) }
            updated.setEnabled("threads_usage_limit", false)
            updated.incrementBlockedCount("ig_usage_limit")
            val changed = withTimeout(5_000) {
                updated.settings.filterNotNull().first { !it.isEnabled("threads_usage_limit") && it.blockedCount("ig_usage_limit") == 1 }
            }
            assertFalse(changed.isEnabled("yt_shorts"))
            assertEquals(1, changed.blockedCount("yt_shorts"))
            (limitIds - "threads_usage_limit").forEach { assertTrue(changed.isEnabled(it)) }
            (limitIds - "ig_usage_limit").forEach { assertEquals(0, changed.blockedCount(it)) }
        } finally { scope.cancel() }
    }

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

    @Test fun addingFacebookPreservesEachOldSwitchCounterAndCustomOrder() = runBlocking {
        val file = temporary.newFile("grouped.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val oldDefinitions = definitions + ModuleDefinition("yt_ad_skip", 3, defaultEnabled = false)
            val old = ToolboxPreferences(store, scope, oldDefinitions)
            old.setEnabled("yt_shorts", false)
            old.setEnabled("yt_ad_skip", true)
            old.incrementBlockedCount("yt_shorts")
            old.incrementBlockedCount("ig_reels")
            old.incrementBlockedCount("ig_reels")
            old.incrementBlockedCount("yt_ad_skip")
            old.setModuleOrder(listOf("yt_ad_skip", "ig_reels", "yt_shorts"))
            val updated = ToolboxPreferences(store, scope, oldDefinitions + ModuleDefinition("fb_reels", 4, defaultEnabled = false))
            val settings = withTimeout(5_000) { updated.settings.filterNotNull().first() }
            assertFalse(settings.isEnabled("yt_shorts"))
            assertTrue(settings.isEnabled("ig_reels"))
            assertTrue(settings.isEnabled("yt_ad_skip"))
            assertFalse(settings.isEnabled("fb_reels"))
            assertEquals(1, settings.blockedCount("yt_shorts"))
            assertEquals(2, settings.blockedCount("ig_reels"))
            assertEquals(1, settings.blockedCount("yt_ad_skip"))
            assertEquals(listOf("yt_ad_skip", "ig_reels", "yt_shorts", "fb_reels"), settings.moduleOrder)
            updated.setEnabled("fb_reels", true)
            val changed = withTimeout(5_000) { updated.settings.filterNotNull().first { it.isEnabled("fb_reels") } }
            assertFalse(changed.isEnabled("yt_shorts"))
            assertTrue(changed.isEnabled("ig_reels"))
        } finally { scope.cancel() }
    }

    @Test fun newAdModuleAppendsToSavedOrderAndStartsDisabled() = runBlocking {
        val file = temporary.newFile("new-module.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val old = ToolboxPreferences(store, scope, definitions)
            old.setModuleOrder(listOf("ig_reels", "yt_shorts"))
            old.incrementBlockedCount("yt_shorts")
            val updated = ToolboxPreferences(store, scope, definitions + ModuleDefinition("yt_ad_skip", 3, defaultEnabled = false))
            val settings = withTimeout(5_000) { updated.settings.filterNotNull().first() }
            assertEquals(listOf("ig_reels", "yt_shorts", "yt_ad_skip"), settings.moduleOrder)
            assertEquals(1, settings.blockedCount("yt_shorts"))
            assertFalse(settings.isEnabled("yt_ad_skip"))
            assertEquals(0, settings.blockedCount("yt_ad_skip"))
        } finally { scope.cancel() }
    }
}
