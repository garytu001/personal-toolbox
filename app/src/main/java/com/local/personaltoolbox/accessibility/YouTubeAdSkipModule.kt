@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.accessibility.ads.AdSkipController
import com.local.personaltoolbox.accessibility.ads.AdSkipHost
import com.local.personaltoolbox.accessibility.ads.AdSkipSampler
import com.local.personaltoolbox.accessibility.ads.AdSkipTarget
import com.local.personaltoolbox.accessibility.ads.AdSkipTargetFinder
import com.local.personaltoolbox.accessibility.ads.TapPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class YouTubeAdSkipModule : ToolboxModule("yt_ad_skip", AdSkipTargetFinder.PACKAGE_NAME) {
    private var controller: AdSkipController? = null

    internal override fun connect(environment: ModuleEnvironment) {
        controller?.cancel()
        val sampler = AdSkipSampler()
        val service = environment.service
        val host = object : AdSkipHost {
            override fun isAllowed(): Boolean {
                if (environment.settings()?.isEnabled(id) != true ||
                    !service.getSystemService(PowerManager::class.java).isInteractive ||
                    service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
                val root = service.rootInActiveWindow ?: return false
                return try { root.packageName?.toString() == packageName } finally { root.recycle() }
            }
            override fun currentTarget(): AdSkipTarget? {
                val root = service.rootInActiveWindow ?: return null
                return try { AdSkipTargetFinder.find(root) } finally { root.recycle() }
            }
            override suspend fun tap(target: AdSkipTarget, point: TapPoint): Boolean {
                if (!isAllowed() || currentTarget() != target || !target.bounds.contains(point)) return false
                return try {
                    val path = Path().apply { moveTo(point.x, point.y) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                    suspendCancellableCoroutine { continuation ->
                        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                if (continuation.isActive) continuation.resume(true)
                            }
                            override fun onCancelled(gestureDescription: GestureDescription) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }, null)
                        if (!accepted && continuation.isActive) continuation.resume(false)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: RuntimeException) { Log.w(TAG, "Skip gesture unavailable", e); false }
            }
            override fun onSkipped() { environment.recordSuccess(id) }
        }
        controller = AdSkipController(environment.scope, host, sampler::delayMillis, sampler::point)
    }

    internal override fun observeWindow(root: AccessibilityNodeInfo) {
        controller?.observe(AdSkipTargetFinder.find(root))
    }
    internal override fun onUserInteraction() { controller?.onUserInteraction() }
    internal override fun cancelPending() { controller?.cancel() }
    override fun matches(root: AccessibilityNodeInfo) = controller?.canSchedule() == true
    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        controller?.schedule()
        return false // Async success is recorded only after gesture completion and disappearance of the control.
    }
    companion object { private const val TAG = "ToolboxAdSkip" }
}
