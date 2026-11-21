package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.data.ToolboxSettings

abstract class ToolboxModule(val id: String, val packageName: String) {
    open val packageNames: Set<String> get() = setOf(packageName)
    // Opt in only when leaving for an unregistered application must be observed.
    internal open val observesForegroundChanges: Boolean = false
    private var lastActionTime: Long? = null
    internal open fun connect(environment: ModuleEnvironment) = Unit
    internal open fun onForegroundChanged(packageName: String?) = Unit
    internal open fun onForegroundLost() { cancelPending() }
    internal open fun onEnabledChanged(enabled: Boolean) { if (!enabled) cancelPending() }
    internal open fun observeWindow(root: AccessibilityNodeInfo) = Unit
    internal open fun onUserInteraction(eventType: Int) = Unit
    internal open fun cancelPending() = Unit

    fun handle(service: AccessibilityService, root: AccessibilityNodeInfo, settings: ToolboxSettings): Boolean {
        if (root.packageName?.toString() !in packageNames || !settings.isEnabled(id)) return false
        val now = SystemClock.elapsedRealtime()
        if (lastActionTime?.let { now - it < COOLDOWN_MS } == true) return false
        if (!matches(root)) return false
        // Mark before acting: UI events caused by BACK/click cannot re-enter immediately.
        lastActionTime = now
        return act(service, root)
    }

    protected abstract fun matches(root: AccessibilityNodeInfo): Boolean
    protected abstract fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean

    companion object { const val COOLDOWN_MS = 450L }
}
