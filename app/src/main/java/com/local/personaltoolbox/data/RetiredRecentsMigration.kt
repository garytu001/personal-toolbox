package com.local.personaltoolbox.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Delete the retired module's data only; all other settings/counts retain their stable IDs. */
internal class RetiredRecentsMigration(private val context: Context? = null) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.any { it.name.startsWith(PREFIX) } ||
            currentData[ToolboxPreferences.MODULE_ORDER]?.split(',')?.contains(ID) == true ||
            (context != null && withContext(Dispatchers.IO) {
                File(context.noBackupFilesDir, ID + "_identity").exists() ||
                    runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)
            })

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { remove(it) }
        this[ToolboxPreferences.MODULE_ORDER]?.let { order ->
            this[ToolboxPreferences.MODULE_ORDER] = order.split(',').filterNot { it == ID }.joinToString(",")
        }
    }

    override suspend fun cleanUp() {
        val app = context ?: return
        withContext(Dispatchers.IO) {
            AtomicFile(File(app.noBackupFilesDir, ID + "_identity")).delete()
            try { keyStore().deleteEntry(ALIAS) }
            catch (e: Exception) { Log.w("RetiredSettings", "Old credential cleanup deferred", e) }
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object {
        const val ID = "recents_control"
        const val PREFIX = ID + "_"
        const val ALIAS = ID + "_adb_storage"
    }
}
