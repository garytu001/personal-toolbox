@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.modules.usagelimit

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.accessibility.ModuleEnvironment
import com.local.personaltoolbox.accessibility.ToolboxModule
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.modules.recents.RemovalResult

class AppUsageLimitModule(id: String, packageName: String, private val minutes: Int) : ToolboxModule(id, packageName) {
    internal override val observesForegroundChanges = true
    private var timer: ContinuousUseTimer? = null
    private var environment: ModuleEnvironment? = null

    internal override fun connect(environment: ModuleEnvironment) {
        timer?.cancel()
        this.environment = environment
        timer = ContinuousUseTimer(environment.scope, minutes * 60_000L, SystemClock::elapsedRealtime,
            isAllowed = { isTargetForeground(environment) },
            exit = {
                // Both the service and the privileged helper independently verify the foreground.
                try {
                    val control = (environment.service.application as ToolboxApplication).recentsControl
                    if (!isTargetForeground(environment)) false
                    else if (control.isReady()) {
                        control.remove(packageName, foregroundOnly = true) { isTargetForeground(environment) } == RemovalResult.REMOVED
                    } else {
                        // Preserve the existing time limit when the optional connection is unavailable.
                        environment.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to leave foreground application", e)
                    false
                }
            },
            onExited = { environment.recordSuccess(id) }, cooldownMillis = COOLDOWN_MS)
    }

    internal override fun onForegroundChanged(packageName: String?) {
        timer?.observe(packageName == this.packageName && environment?.settings()?.isEnabled(id) == true)
    }

    internal override fun onEnabledChanged(enabled: Boolean) { if (!enabled) timer?.cancel() }

    // Generic action cancellation applies to gestures/media commands. A tap, scroll or another
    // module's action must not reset continuous foreground usage; lifecycle uses the hooks above.
    override fun matches(root: AccessibilityNodeInfo) = false
    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo) = false

    private fun isTargetForeground(env: ModuleEnvironment): Boolean {
        return try {
            if (env.settings()?.isEnabled(id) != true ||
                !env.service.getSystemService(PowerManager::class.java).isInteractive ||
                env.service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            val root = env.service.rootInActiveWindow ?: return false
            try { root.packageName?.toString() == packageName } finally { root.recycle() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to verify foreground application", e)
            false
        }
    }

    companion object { private const val TAG = "AppUsageLimit" }
}
