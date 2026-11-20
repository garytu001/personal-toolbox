@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility.ads

import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.accessibility.NodeTools

/** Player-specific markers, never generic "Sponsored" text on the home feed. */
internal object YouTubeAdDetector {
    const val PACKAGE_NAME = "com.google.android.youtube"
    private val adIds = listOf("ad_progress_text", "skip_ad_button", "skip_ad_button_text",
        "ad_skip_button", "video_ad_skip_button")
    private val shortsIds = listOf("reel_recycler", "reel_watch_fragment_root", "reel_player_page_container")

    fun find(root: AccessibilityNodeInfo): AdPlaybackTarget? {
        if (root.packageName?.toString() != PACKAGE_NAME) return null
        if (isShorts(root)) return null
        return if (adIds.any { NodeTools.hasVisibleId(root, "$PACKAGE_NAME:id/$it") })
            AdPlaybackTarget(root.windowId) else null
    }

    fun isShorts(root: AccessibilityNodeInfo): Boolean =
        shortsIds.any { NodeTools.hasVisibleId(root, "$PACKAGE_NAME:id/$it") } ||
            NodeTools.hasSelectedBottomLabel(root, "Shorts", includeText = true)
}
