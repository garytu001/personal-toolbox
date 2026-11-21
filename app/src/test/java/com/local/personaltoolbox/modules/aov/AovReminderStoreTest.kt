package com.local.personaltoolbox.modules.aov

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.local.personaltoolbox.data.ToolboxPreferences
import com.local.personaltoolbox.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AovReminderStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentConfirmationsOnlyClaimOnceAndSurviveWrapperRecreation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { temporary.newFile("aov.preferences_pb") }
            val prefs = ToolboxPreferences(data, scope, listOf(ModuleDefinition(AovReminder.ID, 1, false)))
            prefs.setEnabled(AovReminder.ID, true)
            val date = AovReminderTime.today().toString()
            val repository = AovReminderStore(prefs)
            val claims = (1..20).map { async(Dispatchers.Default) { repository.claimOpen(date) } }.awaitAll()
            assertEquals(1, claims.count { it })
            assertTrue(AovReminderStore(prefs).hasOpenedToday())
            assertFalse(AovReminderStore(prefs).claimOpen(date))
        } finally { scope.cancel() }
    }

    @Test fun disabledAndStaleConfirmationsCannotLaunchAndFailedLaunchCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { temporary.newFile("guard.preferences_pb") }
            val prefs = ToolboxPreferences(data, scope, listOf(ModuleDefinition(AovReminder.ID, 1, false)))
            val repository = AovReminderStore(prefs)
            val date = AovReminderTime.today().toString()
            assertFalse(repository.claimOpen(date))
            prefs.setEnabled(AovReminder.ID, true)
            assertFalse(repository.claimOpen(AovReminderTime.today().minusDays(1).toString()))
            assertTrue(repository.claimOpen(date))
            repository.releaseOpen(date)
            assertTrue(repository.claimOpen(date))
        } finally { scope.cancel() }
    }

    @Test fun durableCommitCannotBeReleasedOrRepeatedAndCompletionIsIdempotent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temporary.newFile("commit.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val prefs = ToolboxPreferences(data, scope, listOf(ModuleDefinition(AovReminder.ID, 1, false)))
            prefs.setEnabled(AovReminder.ID, true)
            val repository = AovReminderStore(prefs)
            val date = AovReminderTime.today().toString()
            assertFalse(repository.commitPurchase(date))
            assertTrue(repository.claimOpen(date))
            assertTrue(repository.commitPurchase(date))
            repository.releaseOpen(date)
            val restored = AovReminderStore(prefs)
            assertFalse(restored.claimOpen(date))
            assertFalse(restored.commitPurchase(date))
            assertTrue(restored.completePurchase(date))
            assertFalse(restored.completePurchase(date))
            assertTrue(restored.hasOpenedToday())
        } finally { scope.cancel() }
    }
}
