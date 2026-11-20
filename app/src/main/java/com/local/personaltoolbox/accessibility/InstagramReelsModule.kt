package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class InstagramReelsModule : ToolboxModule("ig_reels", PACKAGE_NAME) {
    override fun matches(root: AccessibilityNodeInfo): Boolean =
        VIEW_IDS.any { NodeTools.hasVisibleId(root, it, excludeStories = true) } ||
            NodeTools.hasSelectedId(root, "$PACKAGE_NAME:id/clips_tab", excludeStories = true) ||
            NodeTools.hasSelectedBottomLabel(root, "Reels", includeText = false, excludeStories = true)

    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean =
        NodeTools.clickHomeTab(root) || service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    companion object {
        const val PACKAGE_NAME = "com.instagram.android"
        // Stories' reel_viewer is deliberately never used as a trigger.
        private val VIEW_IDS = listOf(
            "$PACKAGE_NAME:id/clips_viewer_view_pager",
            "$PACKAGE_NAME:id/clips_video_container",
        )
    }
}
