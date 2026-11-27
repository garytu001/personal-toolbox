package com.local.personaltoolbox.modules.usagelimit

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.local.personaltoolbox.data.ToolboxPreferences
import com.local.personaltoolbox.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageLimitStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val definitions = listOf(ModuleDefinition("yt_usage_limit", 1), ModuleDefinition("ig_usage_limit", 2))

    @Test fun upgradeDefaultsToNoBlockAndPreservesSettingsCountsAndOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temporary.newFile("upgrade.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val preferences = ToolboxPreferences(data, scope, definitions)
            preferences.setEnabled("yt_usage_limit", false)
            preferences.incrementBlockedCount("ig_usage_limit")
            preferences.setModuleOrder(listOf("ig_usage_limit", "yt_usage_limit"))
            val store = UsageLimitStore(preferences)
            assertEquals(0L, store.remaining(10_000))
            store.start(10_000)
            val state = withTimeout(5_000) { preferences.settings.filterNotNull().first { it.blockedCount("ig_usage_limit") == 1 && !it.isEnabled("yt_usage_limit") && it.moduleOrder == listOf("ig_usage_limit", "yt_usage_limit") } }
            assertFalse(state.isEnabled("yt_usage_limit"))
            assertEquals(1, state.blockedCount("ig_usage_limit"))
            assertEquals(listOf("ig_usage_limit", "yt_usage_limit"), state.moduleOrder)
            assertEquals(60_000L, store.remaining(10_000))
            assertEquals(60_000L, UsageLimitStore(preferences).remaining(10_000))
        } finally { scope.cancel() }
    }

    @Test fun serviceOrAppRestartRestoresOnlyRemainingBlockAndClockRollbackCannotExtendIt() = runBlocking {
        val file = temporary.newFile("restart.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        UsageLimitStore(ToolboxPreferences(first, firstScope, definitions)).start(100_000)
        firstScope.cancel()
        firstScope.coroutineContext[Job]?.join()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = UsageLimitStore(ToolboxPreferences(data, scope, definitions))
            assertEquals(20_000L, store.remaining(140_000))
            assertEquals(0L, store.remaining(99_999))
            assertEquals(0L, store.remaining(160_000))
            data.edit { it[UsageLimitStore.UNTIL] = 1_000_000 }
            assertEquals(0L, store.remaining(100_000))
        } finally { scope.cancel() }
    }
}
