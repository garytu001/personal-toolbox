package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.data.ToolboxSettings

abstract class ToolboxModule(val id: String, val packageName: String) {
    private var lastActionTime: Long? = null

    fun handle(service: AccessibilityService, root: AccessibilityNodeInfo, settings: ToolboxSettings): Boolean {
        if (root.packageName?.toString() != packageName || !settings.isEnabled(id)) return false
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
