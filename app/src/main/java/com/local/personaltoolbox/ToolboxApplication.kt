package com.local.personaltoolbox

import android.app.Application
import com.local.personaltoolbox.data.ToolboxPreferences
import com.local.personaltoolbox.update.OtaUpdater
import com.local.personaltoolbox.modules.ModuleRegistry
import com.local.personaltoolbox.modules.recents.RecentsControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ToolboxApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences by lazy { ToolboxPreferences(this, applicationScope) }
    val updater by lazy { OtaUpdater(this, applicationScope) }
    val recentsControl by lazy { RecentsControl(this) }

    override fun onCreate() {
        super.onCreate()
        ModuleRegistry.modules.forEach { it.initialize?.invoke(this) }
    }
}
