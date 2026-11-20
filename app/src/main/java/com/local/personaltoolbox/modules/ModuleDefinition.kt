package com.local.personaltoolbox.modules

import androidx.annotation.StringRes
import com.local.personaltoolbox.accessibility.ToolboxModule
import com.local.personaltoolbox.R

data class ModuleDefinition(
    val id: String,
    @StringRes val nameRes: Int,
    val defaultEnabled: Boolean = true,
    val createService: (() -> ToolboxModule)? = null,
    @StringRes val countLabelRes: Int = R.string.module_count,
) {
    init { require(id.matches(Regex("[a-z0-9_]+"))) { "Module IDs must be stable lowercase identifiers." } }
}
