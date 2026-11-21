@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.modules.aov

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.accessibility.ModuleEnvironment
import com.local.personaltoolbox.accessibility.ToolboxModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale

class AovAutomationModule : ToolboxModule(AovReminder.ID, AovReminder.GAME_PACKAGE) {
    override val packageNames = setOf(AovReminder.GAME_PACKAGE, LAUNCHER_PACKAGE)
    private var job: Job? = null
    private var ownGesture = false
    private var environment: ModuleEnvironment? = null
    private var handledRequest: String? = null

    internal override fun connect(environment: ModuleEnvironment) {
        this.environment = environment
        AovRunRequests.ready = Build.VERSION.SDK_INT >= 30 &&
            (environment.service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0 &&
            (environment.service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        environment.scope.coroutineContext[Job]?.invokeOnCompletion {
            if (this.environment === environment) AovRunRequests.ready = false
        }
    }

    internal override fun observeWindow(root: AccessibilityNodeInfo) {
        val env = environment ?: return
        val request = AovRunRequests.requests.value ?: return
        if (root.packageName?.toString() == packageName && handledRequest != request.key && job?.isActive != true) {
            handledRequest = request.key
            AovRunRequests.markStarted(request)
            job = env.scope.launch { execute(env, request) }
        }
    }

    private suspend fun execute(env: ModuleEnvironment, request: AovRunRequest) {
        val app = env.service.application as ToolboxApplication
        val store = AovReminderStore(app.preferences)
        var committed = false
        var done = false
        try {
            if (Build.VERSION.SDK_INT < 30) error("screenshot_unavailable")
            if (request.cleanupOnly) {
                check(store.hasConfirmedSuccessToday())
                done = true
                withTimeout(20_000) { delay(1_500); removeGameFromRecents(env, request) }
                AovRunRequests.finish(request, "今日已購買，已移除遊戲卡片")
                return
            }
            check(env.service.packageManager.getPackageInfo(packageName, 0).versionName == VERIFIED_GAME_VERSION) { "game_version_changed" }
            val vision = AovVision(env.service.assets.open("aov/asus_2400x1080_v164111.mask").bufferedReader().use { it.readLines() })
            withTimeout(120_000) {
                AovPurchaseFlow(object : AovPurchaseFlow.Host {
                    override suspend fun screen(afterMillis: Long): AovScreen {
                        delay(afterMillis)
                        checkAllowed(env, request, packageName)
                        val bitmap = screenshot(env.service)
                        try {
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            return vision.recognize(bitmap.width, bitmap.height, pixels).also {
                                Log.i(TAG, "Screen: $it")
                                if (it == AovScreen.UNKNOWN) Log.w(TAG, "Frame ${bitmap.width}x${bitmap.height}: ${vision.lastScores}")
                            }
                        } finally { bitmap.recycle() }
                    }
                    override suspend fun tap(x: Int, y: Int) {
                        checkAllowed(env, request, packageName)
                        gesture(env.service, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 70)
                    }
                    override suspend fun commitPurchase(): Boolean {
                        checkAllowed(env, request, packageName)
                        return store.commitPurchase(request.date).also { committed = it }
                    }
                    override suspend fun purchased() {
                        if (store.completePurchase(request.date)) env.recordSuccess(id)
                        done = true
                        AovRunRequests.status.value = "已購買 1 個魔法水晶（150 寶石）"
                    }
                    override suspend fun alreadyPurchased() {
                        store.markAlreadyPurchased(request.date)
                        done = true
                        AovRunRequests.status.value = "今日已購買，不重複購買"
                    }
                    override suspend fun cleanup() { removeGameFromRecents(env, request) }
                }).run()
            }
            AovRunRequests.finish(request, "今日已購買，已移除遊戲卡片")
        } catch (e: CancellationException) {
            AovRunRequests.finish(request, if (committed && !done) "已停止：購買結果待確認，今天不再自動購買" else if (done) "今日已購買；清理已中止" else "已停止")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Workflow stopped: ${e.message}")
            val message = when {
                committed && !done -> "購買結果待確認，今天不再自動購買"
                done -> "今日已購買；最近使用卡片未確認移除"
                e.message == "game_version_changed" -> "遊戲版本已變更，需重新核對畫面"
                else -> "畫面未辨識或流程中止，未送出購買"
            }
            AovRunRequests.finish(request, message)
        } finally {
            if (!committed && !done) withContext(NonCancellable) { store.releaseOpen(request.date) }
            ownGesture = false
        }
    }

    private fun checkAllowed(env: ModuleEnvironment, request: AovRunRequest, expectedPackage: String) {
        check(env.settings()?.isEnabled(id) == true && AovRunRequests.requests.value == request && AovReminderTime.isCurrentDay(request.date))
        check(env.service.getSystemService(PowerManager::class.java).isInteractive && !env.service.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val root = env.service.rootInActiveWindow ?: error("no_window")
        try { check(root.packageName?.toString() == expectedPackage) { "foreground_changed" } }
        finally { root.recycle() }
    }

    @RequiresApi(30)
    private suspend fun screenshot(service: AccessibilityService): Bitmap = withTimeout(3_000) {
        suspendCancellableCoroutine { continuation ->
            check(Build.VERSION.SDK_INT >= 30)
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("empty_screenshot")
                        val copy = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        if (continuation.isActive) continuation.resume(copy) else copy.recycle()
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    finally { buffer.close() }
                }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("screenshot_$errorCode")) }
            })
        }
    }

    private suspend fun gesture(service: AccessibilityService, path: Path, duration: Long) {
        ownGesture = true
        try {
            withTimeout(2_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val dispatched = service.dispatchGesture(GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
                            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("gesture_cancelled")) }
                        }, null)
                    if (!dispatched && continuation.isActive) continuation.resumeWithException(IllegalStateException("gesture_unavailable"))
                }
            }
            delay(300)
        } finally { ownGesture = false }
    }

    private suspend fun removeGameFromRecents(env: ModuleEnvironment, request: AovRunRequest) {
        checkAllowed(env, request, packageName)
        ownGesture = true
        try {
            check(env.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            delay(1_200)
            checkAllowed(env, request, LAUNCHER_PACKAGE)
            check(env.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
            delay(1_200)
        } finally { ownGesture = false }
        checkAllowed(env, request, LAUNCHER_PACKAGE)
        val root = env.service.rootInActiveWindow ?: error("no_recents")
        ownGesture = true
        try {
            try {
                check(findGameCards(root).first == 1) { "game_card_missing" }
                check(dismissGameCard(root)) { "close_action_unavailable" }
            } finally { root.recycle() }
            delay(1_500)
        } finally { ownGesture = false }
        checkAllowed(env, request, LAUNCHER_PACKAGE)
        val remaining = env.service.rootInActiveWindow ?: error("no_recents")
        try { check(findGameCards(remaining).first == 0) { "card_still_present" } } finally { remaining.recycle() }
        ownGesture = true
        try { env.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); delay(300) }
        finally { ownGesture = false }
    }

    private fun dismissGameCard(root: AccessibilityNodeInfo): Boolean {
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 40 || !node.isVisibleToUser) return false
            if (node.viewIdResourceName == "$LAUNCHER_PACKAGE:id/snapshot" && node.contentDescription?.toString() == "傳說對決") {
                val card = node.parent ?: return false
                try {
                    if (card.viewIdResourceName != "$LAUNCHER_PACKAGE:id/task_view_single") return false
                    Log.i(TAG, "Game card actions: ${card.actionList.map { it.label?.toString() ?: it.id.toString() }}")
                    val close = card.actionList.firstOrNull {
                        it.id == AccessibilityNodeInfo.ACTION_DISMISS || it.label?.toString()?.trim()?.lowercase(Locale.ROOT) in
                            setOf("close", "close app", "dismiss", "關閉", "关闭", "移除")
                    } ?: return false
                    return card.performAction(close.id)
                } finally { card.recycle() }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return visit(root, 0)
    }

    private fun findGameCards(root: AccessibilityNodeInfo): Pair<Int, Rect?> {
        if (root.packageName?.toString() != LAUNCHER_PACKAGE) return -1 to null
        var found: Rect? = null
        var matches = 0
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1_000 || depth > 40 || !node.isVisibleToUser) return
            if (node.viewIdResourceName == "$LAUNCHER_PACKAGE:id/snapshot" && node.contentDescription?.toString() == "傳說對決") {
                matches++
                found = Rect().also(node::getBoundsInScreen)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, 0)
        return matches to found
    }

    internal override fun cancelPending() { job?.cancel() }
    internal override fun onUserInteraction(eventType: Int) { if (!ownGesture) cancelPending() }
    override fun matches(root: AccessibilityNodeInfo) = false
    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo) = false

    companion object {
        private const val TAG = "AovAutomation"
        const val LAUNCHER_PACKAGE = "com.asus.launcher"
        const val VERIFIED_GAME_VERSION = "1.64.1.11"
    }
}
