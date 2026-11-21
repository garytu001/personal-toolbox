package com.local.personaltoolbox.modules.aov

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.local.personaltoolbox.data.ToolboxPreferences
import kotlinx.coroutines.flow.first

/** Uses the Application's existing DataStore; no second file or instance. */
internal class AovReminderStore(private val preferences: ToolboxPreferences) {
    suspend fun hasOpenedToday(): Boolean = preferences.store.data.first()[LAST_OPEN] == AovReminderTime.today().toString()
    suspend fun hasConfirmedSuccessToday(): Boolean = preferences.store.data.first()[LAST_SUCCESS] == AovReminderTime.today().toString()

    suspend fun claimOpen(date: String): Boolean {
        if (!AovReminderTime.isCurrentDay(date)) return false
        var claimed = false
        preferences.store.edit {
            if (it[ToolboxPreferences.enabledKey(AovReminder.ID)] == true && it[LAST_OPEN] != date) {
                it[LAST_OPEN] = date
                claimed = true
            }
        }
        return claimed
    }

    suspend fun releaseOpen(date: String) {
        preferences.store.edit { if (it[LAST_OPEN] == date && it[LAST_COMMIT] != date && it[LAST_SUCCESS] != date) it.remove(LAST_OPEN) }
    }

    suspend fun commitPurchase(date: String): Boolean {
        if (!AovReminderTime.isCurrentDay(date)) return false
        var accepted = false
        preferences.store.edit {
            if (it[ToolboxPreferences.enabledKey(AovReminder.ID)] == true && it[LAST_OPEN] == date &&
                it[LAST_COMMIT] != date && it[LAST_SUCCESS] != date) {
                it[LAST_COMMIT] = date
                accepted = true
            }
        }
        return accepted
    }

    suspend fun completePurchase(date: String): Boolean {
        var first = false
        preferences.store.edit {
            if (it[LAST_COMMIT] == date && it[LAST_SUCCESS] != date) {
                it[LAST_SUCCESS] = date
                first = true
            }
        }
        return first
    }

    suspend fun markAlreadyPurchased(date: String) {
        preferences.store.edit { it[LAST_OPEN] = date; it[LAST_SUCCESS] = date }
    }

    companion object {
        private val LAST_OPEN = stringPreferencesKey("${AovReminder.ID}_last_open_date")
        private val LAST_COMMIT = stringPreferencesKey("${AovReminder.ID}_last_commit_date")
        private val LAST_SUCCESS = stringPreferencesKey("${AovReminder.ID}_last_success_date")
    }
}
