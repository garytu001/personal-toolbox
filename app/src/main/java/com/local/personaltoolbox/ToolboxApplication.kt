package com.local.personaltoolbox

import android.app.Application
import com.local.personaltoolbox.data.ToolboxPreferences
import com.local.personaltoolbox.update.OtaUpdater
import com.local.personaltoolbox.modules.ModuleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.SystemClock
import android.util.Log
import com.local.personaltoolbox.modules.usagelimit.UsageBlockGroup
import com.local.personaltoolbox.modules.usagelimit.UsageLimitStore

class ToolboxApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences by lazy { ToolboxPreferences(this, applicationScope) }
    val updater by lazy { OtaUpdater(this, applicationScope) }
    internal val usageBlockGroup by lazy {
        val store = UsageLimitStore(preferences)
        UsageBlockGroup(SystemClock::elapsedRealtime,
            loadRemaining = { store.remaining(System.currentTimeMillis()) },
            persist = { remaining ->
                val startedAt = System.currentTimeMillis()
                applicationScope.launch {
                    try { store.start(startedAt, remaining) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Log.e("UsageBlockGroup", "Unable to persist shared break", e) }
                }
            })
    }

    override fun onCreate() {
        super.onCreate()
        ModuleRegistry.modules.forEach { it.initialize?.invoke(this) }
    }
}
