package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class YouTubeShortsModule : ToolboxModule("yt_shorts", PACKAGE_NAME) {
    override fun matches(root: AccessibilityNodeInfo): Boolean =
        VIEW_IDS.any { NodeTools.hasVisibleId(root, it) } ||
            NodeTools.hasSelectedBottomLabel(root, "Shorts", includeText = true)

    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    companion object {
        const val PACKAGE_NAME = "com.google.android.youtube"
        private val VIEW_IDS = listOf(
            "$PACKAGE_NAME:id/reel_recycler",
            "$PACKAGE_NAME:id/reel_watch_fragment_root",
            "$PACKAGE_NAME:id/reel_player_page_container",
        )
    }
}
