@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.modules.recents

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Visible ASUS Recents: logo long-click -> app's own Close menu -> verify disappearance.
 * Each step resumes on a real accessibility event, without a polling loop or fixed sleep.
 */
internal class RecentCardCloser(private val service: AccessibilityService) {
    private enum class Stage { OPENING, MENU, VERIFY }
    private val lock = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var stage: Stage? = null
    private var interrupted = false
    private var ownLongClickPending = false
    private var ownClosePending = false
    private var openingPackage: String? = null

    fun cancel() { interrupted = true; changes.trySend(Unit) }

    fun onEvent(event: AccessibilityEvent) {
        val current = stage ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            val source = event.source
            val ownAction = try {
                if (source?.packageName?.toString() != RecentApps.LAUNCHER) false
                else when {
                    ownLongClickPending && event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
                        source.viewIdResourceName == "${RecentApps.LAUNCHER}:id/icon" -> {
                        ownLongClickPending = false
                        true
                    }
                    ownClosePending && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && hasCloseLabel(source) -> {
                        ownClosePending = false
                        true
                    }
                    else -> false
                }
            } finally { source?.recycle() }
            if (!ownAction) interrupted = true
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && current == Stage.MENU) {
            interrupted = true
        }
        changes.trySend(Unit)
    }

    suspend fun close(packageName: String, foregroundOnly: Boolean,
                      mayOpen: () -> Boolean = { true }, allowed: () -> Boolean): Boolean {
        val fallback = RecentApps.labels[packageName] ?: return false
        if (!lock.tryLock()) return false
        try {
            interrupted = false
            ownLongClickPending = false
            ownClosePending = false
            while (changes.tryReceive().isSuccess) { /* discard stale events */ }
            checkAllowed(allowed)
            val initial = service.rootInActiveWindow ?: return false
            val initialPackage = try { initial.packageName?.toString() } finally { initial.recycle() }
            if (foregroundOnly && initialPackage != packageName) return false
            if (!foregroundOnly && initialPackage !in setOf(packageName, service.packageName, RecentApps.LAUNCHER)) return false
            openingPackage = initialPackage
            val labels = setOf(fallback, installedLabel(packageName)).filter { it.isNotBlank() }.toSet()
            checkAllowed(allowed)
            if (!mayOpen()) return false
            val beforeOpen = service.rootInActiveWindow ?: return false
            try { if (beforeOpen.packageName?.toString() != initialPackage) return false }
            finally { beforeOpen.recycle() }
            stage = Stage.OPENING
            if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return false
            return withTimeoutOrNull(6_000) {
                var centered = false
                val icon = awaitNode(allowed) { root ->
                    if (!RecentCardTree.overview(root)) return@awaitNode null
                    RecentCardTree.icon(root, labels) ?: run {
                        // A partially visible, uniquely identified card may expose this standard action.
                        // Do not launch the game or guess a swipe/coordinate to search for it.
                        val card = RecentCardTree.cards(root, labels).singleOrNull()
                        if (!centered && card != null) {
                            centered = true
                            perform(card.path, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id, allowed) { fresh ->
                                RecentCardTree.cards(fresh, labels).singleOrNull()?.path == card.path
                            }
                        }
                        null
                    }
                }
                stage = Stage.MENU // Set before dispatch; the own long-click event can arrive immediately.
                ownLongClickPending = true
                if (!perform(icon.path, AccessibilityNodeInfo.ACTION_LONG_CLICK, allowed) {
                        RecentCardTree.icon(it, labels)?.path == icon.path
                    }) return@withTimeoutOrNull false
                val close = awaitNode(allowed) { RecentCardTree.close(it, labels) }
                stage = Stage.VERIFY
                ownClosePending = true
                if (!perform(close.path, AccessibilityNodeInfo.ACTION_CLICK, allowed) {
                        RecentCardTree.close(it, labels)?.path == close.path
                    }) return@withTimeoutOrNull false
                awaitNode(allowed) { root ->
                    if (RecentCardTree.removed(root, labels)) root else null
                }
                checkAllowed(allowed)
                // Only leave the launcher. Never send HOME after a user has entered another app.
                val root = service.rootInActiveWindow
                try {
                    if (root?.packageName?.toString() != RecentApps.LAUNCHER) return@withTimeoutOrNull false
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                } finally { root?.recycle() }
                true
            } ?: false
        } catch (e: CancellationException) { throw e }
        catch (_: RuntimeException) { return false }
        finally { stage = null; openingPackage = null; ownLongClickPending = false; ownClosePending = false; lock.unlock() }
    }

    private suspend fun awaitNode(allowed: () -> Boolean, select: (RecentNode) -> RecentNode?): RecentNode {
        while (true) {
            currentCoroutineContext().ensureActive()
            checkAllowed(allowed)
            val root = service.rootInActiveWindow
            try {
                if (root?.packageName?.toString() == RecentApps.LAUNCHER) {
                    read(root)?.let(select)?.let { return it }
                } else if (root != null && (stage != Stage.OPENING || root.packageName?.toString() != openingPackage)) {
                    error("foreground_changed")
                }
            } finally { root?.recycle() }
            changes.receive() // Event-driven state synchronization; never periodically reads the screen.
        }
    }

    private fun perform(path: List<Int>, action: Int, allowed: () -> Boolean, validate: (RecentNode) -> Boolean): Boolean {
        checkAllowed(allowed)
        val root = service.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != RecentApps.LAUNCHER || !validate(read(root) ?: return false)) return false
            var current = AccessibilityNodeInfo.obtain(root)
            try {
                for (index in path) {
                    val next = current.getChild(index) ?: return false
                    current.recycle()
                    current = next
                }
                checkAllowed(allowed)
                return current.isVisibleToUser && current.isEnabled && current.performAction(action)
            } finally { current.recycle() }
        } finally { root.recycle() }
    }

    private fun checkAllowed(allowed: () -> Boolean) {
        check(!interrupted && allowed() && service.getSystemService(PowerManager::class.java).isInteractive &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "card_close_cancelled" }
    }

    private fun installedLabel(packageName: String): String = try {
        service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "" }

    private fun hasCloseLabel(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.text?.toString()?.trim() in RecentCardTree.closeLabels ||
            node.contentDescription?.toString()?.trim() in RecentCardTree.closeLabels) return true
        if (depth >= 3) return false
        for (i in 0 until node.childCount.coerceAtMost(12)) {
            val child = node.getChild(i) ?: continue
            try { if (hasCloseLabel(child, depth + 1)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun read(root: AccessibilityNodeInfo): RecentNode? {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        var remaining = 1_200
        fun copy(node: AccessibilityNodeInfo, path: List<Int>): RecentNode? {
            if (--remaining < 0 || path.size > 40) error("launcher_tree_too_large")
            if (!node.isVisibleToUser || !node.isEnabled) return null
            val id = node.viewIdResourceName.orEmpty()
            val children = mutableListOf<RecentNode>()
            // Snapshot metadata identifies the card; its application content is never traversed.
            if (id != "${RecentApps.LAUNCHER}:id/snapshot") {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { copy(child, path + index)?.let(children::add) } finally { child.recycle() }
                }
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return RecentNode(path, id, node.text?.toString()?.trim().orEmpty(),
                node.contentDescription?.toString()?.trim().orEmpty(), node.isClickable,
                node.isLongClickable, !bounds.isEmpty && screen.contains(bounds), children)
        }
        return copy(root, emptyList())
    }
}
