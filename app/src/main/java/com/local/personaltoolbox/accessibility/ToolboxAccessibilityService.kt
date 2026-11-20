@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.modules.ModuleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ToolboxAccessibilityService : AccessibilityService() {
    private val modules = ModuleRegistry.modules.mapNotNull { definition ->
        definition.createService?.invoke()?.also { require(it.id == definition.id) }
    }.groupBy { it.packageName }
    private val app get() = application as ToolboxApplication

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val sourcePackage = event.packageName?.toString() ?: return
        val candidates = modules[sourcePackage] ?: return
        val settings = app.preferences.settings.value ?: return
        if (candidates.none { settings.isEnabled(it.id) }) return
        val root = rootInActiveWindow ?: return
        try {
            // A delayed event must never trigger a BACK/click in a different foreground application.
            if (root.packageName?.toString() != sourcePackage) return
            for (module in candidates) {
                if (module.handle(this, root, settings)) {
                    recordBlock(module.id)
                    break // At most one action per event, even with multiple modules for one package.
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Accessibility window changed during inspection", e)
        } finally { root.recycle() }
    }

    private fun recordBlock(moduleId: String) {
        app.applicationScope.launch {
            try { app.preferences.incrementBlockedCount(moduleId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Unable to persist blocked count", e) }
        }
    }

    override fun onInterrupt() = Unit
    companion object { private const val TAG = "ToolboxAccessibility" }
}
