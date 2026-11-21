@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
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
    private val modules = allModules.flatMap { module -> module.packageNames.map { it to module } }
        .groupBy({ it.first }, { it.second })
    private val foregroundObservers = allModules.filter { it.observesForegroundChanges }
    private val app get() = application as ToolboxApplication
    private var moduleScope: CoroutineScope? = null
    private var allPackageEvents: Boolean? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) notifyForeground(null) else refreshForeground()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        notifyForeground(null)
        moduleScope?.cancel()
        allPackageEvents = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        moduleScope = scope
        val environment = ModuleEnvironment(this, scope, { app.preferences.settings.value }, ::recordBlock)
        allModules.forEach { it.connect(environment) }
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        scope.launch {
            app.preferences.settings.collect { settings ->
                allModules.forEach { it.onEnabledChanged(settings?.isEnabled(it.id) == true) }
                val observeAll = foregroundObservers.any { settings?.isEnabled(it.id) == true }
                if (allPackageEvents != observeAll) {
                    // Android's package filter would hide departures to other apps. Only opt-in
                    // foreground observers need those events; action dispatch stays registry-bound.
                    serviceInfo = serviceInfo.apply { packageNames = if (observeAll) null else modules.keys.toTypedArray() }
                    allPackageEvents = observeAll
                }
                refreshForeground()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val sourcePackage = event.packageName?.toString() ?: run { notifyForeground(null); return }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val settings = app.preferences.settings.value ?: return
        val root = rootInActiveWindow ?: run { notifyForeground(null); cancelPending(); return }
        try {
            // The current root is authoritative for foreground lifetime, including delayed events.
            val foregroundPackage = root.packageName?.toString()
            notifyForeground(foregroundPackage)
            // A delayed event must never trigger an action in a different foreground application.
            if (foregroundPackage != sourcePackage) { cancelPending(); return }
            val candidates = modules[sourcePackage] ?: run { cancelPending(); return }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                candidates.forEach { it.onUserInteraction(event.eventType) }
                return
            }
            allModules.filter { sourcePackage !in it.packageNames || !settings.isEnabled(it.id) }.forEach { it.cancelPending() }
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
            notifyForeground(null)
            cancelPending()
            Log.w(TAG, "Accessibility window changed during inspection", e)
        } finally { root.recycle() }
    }

    private fun notifyForeground(packageName: String?) {
        val foreground = if (getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked) packageName else null
        foregroundObservers.forEach { it.onForegroundChanged(foreground) }
    }

    private fun refreshForeground() {
        if (foregroundObservers.isEmpty()) return
        try {
            val root = rootInActiveWindow
            try { notifyForeground(root?.packageName?.toString()) } finally { root?.recycle() }
        } catch (e: RuntimeException) {
            notifyForeground(null)
            Log.w(TAG, "Unable to observe foreground application", e)
        }
    }

    private fun recordBlock(moduleId: String) {
        app.applicationScope.launch {
            try { app.preferences.incrementBlockedCount(moduleId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Unable to persist count", e) }
        }
    }

    private fun cancelPending() { allModules.forEach { it.cancelPending() } }
    private fun stop() {
        notifyForeground(null)
        cancelPending()
        moduleScope?.cancel()
        moduleScope = null
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
    }
    override fun onInterrupt() { notifyForeground(null); cancelPending() }
    override fun onUnbind(intent: Intent?): Boolean { stop(); return super.onUnbind(intent) }
    override fun onDestroy() { stop(); super.onDestroy() }
    companion object { private const val TAG = "ToolboxAccessibility" }
}
