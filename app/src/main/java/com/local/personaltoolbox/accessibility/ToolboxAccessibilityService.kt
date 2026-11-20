@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.modules.ModuleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ToolboxAccessibilityService : AccessibilityService() {
    private val allModules = ModuleRegistry.modules.mapNotNull { definition ->
        definition.createService?.invoke()?.also { require(it.id == definition.id) }
    }
    private val modules = allModules.groupBy { it.packageName }
    private val app get() = application as ToolboxApplication
    private var moduleScope: CoroutineScope? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        moduleScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        moduleScope = scope
        val environment = ModuleEnvironment(this, scope, { app.preferences.settings.value }, ::recordBlock)
        allModules.forEach { it.connect(environment) }
        scope.launch {
            app.preferences.settings.collect { settings ->
                allModules.filter { settings?.isEnabled(it.id) != true }.forEach { it.cancelPending() }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val sourcePackage = event.packageName?.toString() ?: return
        val candidates = modules[sourcePackage] ?: run { cancelPending(); return }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            candidates.forEach { it.onUserInteraction(event.eventType) }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val settings = app.preferences.settings.value ?: return
        val root = rootInActiveWindow ?: run { cancelPending(); return }
        try {
            // A delayed event must never trigger an action in a different foreground application.
            if (root.packageName?.toString() != sourcePackage) { cancelPending(); return }
            allModules.filter { it.packageName != sourcePackage || !settings.isEnabled(it.id) }.forEach { it.cancelPending() }
            val enabled = candidates.filter { settings.isEnabled(it.id) }
            enabled.forEach { it.observeWindow(root) }
            for (module in enabled) {
                if (module.handle(this, root, settings)) {
                    cancelPending()
                    recordBlock(module.id)
                    break
                }
            }
        } catch (e: RuntimeException) {
            cancelPending()
            Log.w(TAG, "Accessibility window changed during inspection", e)
        } finally { root.recycle() }
    }

    private fun recordBlock(moduleId: String) {
        app.applicationScope.launch {
            try { app.preferences.incrementBlockedCount(moduleId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Unable to persist count", e) }
        }
    }

    private fun cancelPending() { allModules.forEach { it.cancelPending() } }
    private fun stop() { cancelPending(); moduleScope?.cancel(); moduleScope = null }
    override fun onInterrupt() { cancelPending() }
    override fun onUnbind(intent: Intent?): Boolean { stop(); return super.onUnbind(intent) }
    override fun onDestroy() { stop(); super.onDestroy() }
    companion object { private const val TAG = "ToolboxAccessibility" }
}
