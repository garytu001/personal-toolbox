package com.local.personaltoolbox.modules

import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.accessibility.ToolboxModule
import com.local.personaltoolbox.R

data class ModuleDefinition(
    val id: String,
    @StringRes val nameRes: Int,
    val defaultEnabled: Boolean = true,
    val createService: (() -> ToolboxModule)? = null,
    @StringRes val countLabelRes: Int = R.string.module_count,
    val action: ModuleAction? = null,
    val initialize: ((ToolboxApplication) -> Unit)? = null,
) {
    init { require(id.matches(Regex("[a-z0-9_]+"))) { "Module IDs must be stable lowercase identifiers." } }
}

data class ModuleAction(
    @StringRes val labelRes: Int,
    val settingsAction: String = "",
    @StringRes val requestLabelRes: Int = labelRes,
    val isGranted: ((Context) -> Boolean)? = null,
    val intentFactory: ((Context) -> Intent)? = null,
) {
    fun intent(context: Context): Intent = intentFactory?.invoke(context) ?: Intent(settingsAction)
}
