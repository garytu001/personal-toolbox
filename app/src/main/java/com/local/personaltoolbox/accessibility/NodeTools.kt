@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal object NodeTools {
    fun hasVisibleId(root: AccessibilityNodeInfo, id: String, excludeStories: Boolean = false): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return try { nodes.any { it.isVisibleToUser && (!excludeStories || !isInsideStory(it)) } }
        finally { nodes.forEach { it.recycle() } }
    }

    fun hasSelectedId(root: AccessibilityNodeInfo, id: String, excludeStories: Boolean = false): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return try { nodes.any { it.isVisibleToUser && it.isSelected && (!excludeStories || !isInsideStory(it)) } }
        finally { nodes.forEach { it.recycle() } }
    }

    fun hasSelectedBottomLabel(root: AccessibilityNodeInfo, label: String, includeText: Boolean, excludeStories: Boolean = false): Boolean {
        val window = Rect().also(root::getBoundsInScreen)
        if (window.isEmpty) return false
        // Labels on selected content cards are not navigation tabs.
        val bottomStart = window.top + window.height() * 0.70
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 12_000 || depth > 80) return false
            if (excludeStories && isStoryId(node.viewIdResourceName.orEmpty())) return false
            if (node.isVisibleToUser && node.isSelected) {
                val descriptionMatches = node.contentDescription?.contains(label, ignoreCase = true) == true
                val textMatches = includeText && node.text?.contains(label, ignoreCase = true) == true
                if (descriptionMatches || textMatches) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty && bounds.centerY() >= bottomStart) return true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return visit(root, 0)
    }

    private fun isStoryId(id: String): Boolean = id.startsWith("com.instagram.android:id/") &&
        (id.contains("reel_viewer") || id.contains("reels_tray") || id.contains("story") || id.contains("stories"))

    private fun isInsideStory(node: AccessibilityNodeInfo): Boolean {
        if (isStoryId(node.viewIdResourceName.orEmpty())) return true
        var parent = node.parent
        repeat(80) {
            val current = parent ?: return false
            val story = isStoryId(current.viewIdResourceName.orEmpty())
            parent = if (story) null else current.parent
            current.recycle()
            if (story) return true
        }
        parent?.recycle()
        return false
    }

    fun clickHomeTab(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/feed_tab")
        return try {
            nodes.any { node ->
                if (!node.isVisibleToUser) return@any false
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@any true
                var parent = node.parent
                var clicked = false
                var depth = 0
                while (parent != null && depth++ < 4) {
                    val current = parent
                    clicked = current.isVisibleToUser && current.isClickable &&
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent = if (!clicked) current.parent else null
                    current.recycle()
                    if (clicked) break
                }
                parent?.recycle()
                clicked
            }
        } finally { nodes.forEach { it.recycle() } }
    }
}
