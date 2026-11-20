@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility.ads

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal object AdSkipTargetFinder {
    const val PACKAGE_NAME = "com.google.android.youtube"
    private val ids = listOf("skip_ad_button", "skip_ad_button_text", "ad_skip_button", "video_ad_skip_button")

    fun find(root: AccessibilityNodeInfo): AdSkipTarget? {
        if (root.packageName?.toString() != PACKAGE_NAME) return null
        val viewport = Rect().also(root::getBoundsInScreen)
        if (viewport.isEmpty) return null
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$PACKAGE_NAME:id/$id")
            try {
                for (node in nodes) candidate(node, viewport, root.windowId)?.let { return it }
            } finally { nodes.forEach { it.recycle() } }
        }
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): AdSkipTarget? {
            if (++visited > 2_000 || depth > 64) return null
            if (AdSkipLabels.explicit(node.text?.toString().orEmpty()) ||
                AdSkipLabels.explicit(node.contentDescription?.toString().orEmpty())) {
                candidate(node, viewport, root.windowId)?.let { return it }
            }
            for (i in 0 until node.childCount) {
                if (visited > 2_000) break
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return visit(root, 0)
    }

    private fun candidate(source: AccessibilityNodeInfo, viewport: Rect, windowId: Int): AdSkipTarget? {
        if (!source.isVisibleToUser || !source.isEnabled || source.packageName?.toString() != PACKAGE_NAME) return null
        val sourceId = source.viewIdResourceName.orEmpty().substringAfter(":id/", "")
        if (source.isClickable) return capture(source, source, sourceId, viewport, windowId)
        var parent = source.parent
        repeat(3) {
            val current = parent ?: return null
            try {
                if (!current.isVisibleToUser || !current.isEnabled) return null
                if (current.isClickable) return capture(source, current, sourceId, viewport, windowId)
                parent = current.parent
            } finally { current.recycle() }
        }
        parent?.recycle()
        return null
    }

    private fun capture(source: AccessibilityNodeInfo, button: AccessibilityNodeInfo, sourceId: String,
                        viewport: Rect, windowId: Int): AdSkipTarget? {
        if (button.packageName?.toString() != PACKAGE_NAME) return null
        val bounds = Rect().also(button::getBoundsInScreen)
        if (bounds.width() < 12 || bounds.height() < 12 || !viewport.contains(bounds) ||
            bounds.width() > viewport.width() * 0.85 || bounds.height() > viewport.height() * 0.30 ||
            bounds.width().toLong() * bounds.height() > viewport.width().toLong() * viewport.height() * 0.15) return null
        val labels = ArrayList<String>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser || depth > 2 || labels.size > 32) return
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { collect(child, depth + 1) } finally { child.recycle() }
                if (labels.size > 32) break
            }
        }
        collect(button, 0)
        val buttonLike = source.className?.toString()?.endsWith("Button") == true ||
            button.className?.toString()?.endsWith("Button") == true ||
            AdSkipLabels.explicit(source.contentDescription?.toString().orEmpty())
        if (!AdSkipLabels.ready(sourceId, labels, button.isEnabled, button.isVisibleToUser, button.isClickable, buttonLike)) return null
        val identity = sourceId + ":" + button.viewIdResourceName.orEmpty() + ":" +
            labels.map(AdSkipLabels::normalized).distinct().sorted().joinToString("|")
        return AdSkipTarget(windowId, identity, TapBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
}
