package com.local.personaltoolbox

import android.app.Application
import com.local.personaltoolbox.data.ToolboxPreferences
import com.local.personaltoolbox.update.OtaUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ToolboxApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences by lazy { ToolboxPreferences(this, applicationScope) }
    val updater by lazy { OtaUpdater(this, applicationScope) }
}
