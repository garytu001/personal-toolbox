package com.local.personaltoolbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.personaltoolbox.modules.ModuleDefinition
import com.local.personaltoolbox.modules.ModuleOrder
import com.local.personaltoolbox.modules.ModuleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import java.io.IOException

private val Context.toolboxDataStore by preferencesDataStore(
    name = "toolbox_preferences", produceMigrations = { listOf(ToolboxMigration) })

data class ModuleState(val enabled: Boolean = true, val blockedCount: Int = 0)
data class ToolboxSettings(val modules: Map<String, ModuleState>, val moduleOrder: List<String>) {
    fun isEnabled(id: String): Boolean = modules[id]?.enabled == true
    fun blockedCount(id: String): Int = modules[id]?.blockedCount ?: 0
}

class ToolboxPreferences internal constructor(
    internal val store: DataStore<Preferences>,
    scope: CoroutineScope,
    definitions: List<ModuleDefinition>,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        context.applicationContext.toolboxDataStore, scope, ModuleRegistry.modules)
    private val byId = definitions.associateBy { it.id }
    private val ids = definitions.map { it.id }
    // A null initial value prevents intercepting before the saved switches are loaded.
    val settings = store.data
        .retryWhen { cause, _ ->
            if (cause is IOException) { delay(1_000); true } else false
        }
        .map { prefs ->
            ToolboxSettings(
                modules = definitions.associate { definition -> definition.id to ModuleState(
                    enabled = prefs[enabledKey(definition.id)] ?: definition.defaultEnabled,
                    blockedCount = (prefs[countKey(definition.id)] ?: 0).coerceAtLeast(0),
                ) },
                moduleOrder = ModuleOrder.resolve(prefs[MODULE_ORDER], ids),
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setEnabled(id: String, enabled: Boolean) {
        require(id in byId)
        store.edit { it[enabledKey(id)] = enabled }
    }

    suspend fun incrementBlockedCount(id: String) {
        require(id in byId)
        val key = countKey(id)
        store.edit { it[key] = ((it[key] ?: 0).coerceAtLeast(0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }

    suspend fun setModuleOrder(order: List<String>) {
        store.edit { it[MODULE_ORDER] = ModuleOrder.encode(order, ids) }
    }

    companion object {
        val MODULE_ORDER = stringPreferencesKey("module_order")
        internal fun enabledKey(id: String) = booleanPreferencesKey("${id}_enabled")
        internal fun countKey(id: String) = intPreferencesKey("${id}_blocked_count")
    }
}
