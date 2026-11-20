package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import com.local.personaltoolbox.data.ToolboxSettings
import kotlinx.coroutines.CoroutineScope

internal class ModuleEnvironment(
    val service: AccessibilityService,
    val scope: CoroutineScope,
    val settings: () -> ToolboxSettings?,
    val recordSuccess: (String) -> Unit,
)
