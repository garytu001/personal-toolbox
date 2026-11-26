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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class AppUsageLimitModule(id: String, packageName: String, private val minutes: Int) : ToolboxModule(id, packageName) {
    internal override val observesForegroundChanges = true
    private var timer: UsageLimitTimer? = null
    private var initialization: Job? = null
    private var environment: ModuleEnvironment? = null

    internal override fun connect(environment: ModuleEnvironment) {
        initialization?.cancel()
        timer?.pause()
        timer = null
        this.environment = environment
        val app = environment.service.application as ToolboxApplication
        initialization = environment.scope.launch {
            try {
                app.preferences.settings.filterNotNull().first()
                val store = UsageLimitStore(app.preferences, id)
                val readAt = SystemClock.elapsedRealtime()
                val savedRemaining = store.remaining(System.currentTimeMillis())
                val remaining = (savedRemaining - (SystemClock.elapsedRealtime() - readAt)).coerceAtLeast(0)
                timer = UsageLimitTimer(environment.scope, minutes * 60_000L, SystemClock::elapsedRealtime,
                    isAllowed = { isTargetForeground(environment) },
                    goHome = {
                        try {
                            isTargetForeground(environment) &&
                                environment.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Unable to leave foreground application", e)
                            false
                        }
                    },
                    onExited = { environment.recordSuccess(id) },
                    onBlockStarted = {
                        val startedAt = System.currentTimeMillis()
                        app.applicationScope.launch {
                            try { store.start(startedAt) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { Log.e(TAG, "Unable to persist temporary block", e) }
                        }
                    },
                    initialBlockMillis = remaining, cooldownMillis = COOLDOWN_MS)
                timer?.observe(isTargetForeground(environment))
                timer?.enforceBlock()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Unable to restore temporary block", e) }
        }
    }

    internal override fun onForegroundChanged(packageName: String?) {
        timer?.observe(packageName == this.packageName && environment?.settings()?.isEnabled(id) == true)
    }

    internal override fun onEnabledChanged(enabled: Boolean) { if (!enabled) timer?.pause() }

    internal override fun onForegroundReady(): Boolean {
        val active = timer ?: return false
        val env = environment ?: return false
        if (!active.isBlocked || !isTargetForeground(env)) return false
        active.enforceBlock()
        return true
    }

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
