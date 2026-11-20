@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.abs

internal data class FacebookReelsSignals(
    val selectedReelsTab: Boolean,
    val reelsHeading: Boolean,
    val fullScreenPager: Boolean,
    val stackedActions: Boolean,
    val storyControls: Boolean,
) {
    fun matches() = !storyControls && (selectedReelsTab || (reelsHeading && fullScreenPager && stackedActions))
}

/** Conservative semantic matching. Facebook strips resource names; never guess numeric IDs or block "Videos". */
class FacebookReelsModule : ToolboxModule("fb_reels", PACKAGE_NAME) {
    override fun matches(root: AccessibilityNodeInfo): Boolean {
        val window = Rect().also(root::getBoundsInScreen)
        if (window.isEmpty) return false
        var selected = false
        var heading = false
        var pager = false
        var stories = false
        val actions = mutableListOf<Pair<String, Int>>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 4_000 || depth > 64) return
            if (!node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim().lowercase(Locale.ROOT) }
            val top = bounds.centerY() < window.top + window.height() * 0.25
            val bottom = bounds.centerY() > window.top + window.height() * 0.70
            val reelLabel = labels.any { it in REELS || NAVIGATION.matches(it) }
            if (reelLabel && node.isSelected && (top || bottom)) selected = true
            if (labels.any { it in REELS } && top) heading = true
            if (node.isScrollable && bounds.height() >= window.height() * 0.80 &&
                bounds.width() >= window.width() * 0.85) pager = true
            if (labels.any { it in STORY_LABELS } ||
                node.viewIdResourceName.orEmpty().substringAfter(":id/").let {
                    it.startsWith("story_viewer") || it.startsWith("stories_viewer")
                }) stories = true
            if (bounds.centerX() > window.left + window.width() * 0.65) {
                labels.forEach { label -> ACTIONS.entries.firstOrNull { (_, names) ->
                    names.any { name -> label == name || label.startsWith("$name,") || label.startsWith("$name，") }
                }?.let { actions.add(it.key to bounds.centerY()) } }
            }
            for (index in 0 until node.childCount) {
                if (visited >= 4_000) break
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, 0)
        val stacked = actions.any { first -> actions.any { second ->
            first.first != second.first && abs(first.second - second.second) >= window.height() * 0.06
        } }
        return FacebookReelsSignals(selected, heading, pager, stacked, stories).matches()
    }

    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean =
        clickHome(root) || service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun clickHome(root: AccessibilityNodeInfo): Boolean {
        val window = Rect().also(root::getBoundsInScreen)
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 2_000 || depth > 64 || !node.isVisibleToUser) return false
            val bounds = Rect().also(node::getBoundsInScreen)
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim().lowercase(Locale.ROOT) }
            val navigationArea = bounds.centerY() < window.top + window.height() * 0.20 ||
                bounds.centerY() > window.top + window.height() * 0.80
            if (node.isClickable && node.isEnabled && navigationArea && !bounds.isEmpty &&
                bounds.width() <= window.width() * 0.30 && bounds.height() <= window.height() * 0.15 &&
                labels.any { it in HOME || HOME_NAVIGATION.matches(it) } &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            for (index in 0 until node.childCount) {
                if (visited >= 2_000) break
                val child = node.getChild(index) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return visit(root, 0)
    }

    companion object {
        const val PACKAGE_NAME = "com.facebook.katana"
        private val REELS = setOf("reels", "連續短片", "连续短片")
        private val HOME = setOf("home", "首頁", "首页")
        private val HOME_NAVIGATION = Regex("^(home|首頁|首页)[,，].*(tab|分頁|分页|標籤|标签).*")
        private val NAVIGATION = Regex("^(reels|連續短片|连续短片)[,，].*(tab|分頁|分页|標籤|标签).*")
        private val STORY_LABELS = setOf("send message", "傳送訊息", "发送消息", "reply to story", "回覆限時動態", "回复快拍")
        private val ACTIONS = mapOf(
            "like" to setOf("like", "讚", "按讚", "赞"),
            "comment" to setOf("comment", "留言", "評論", "评论"),
            "share" to setOf("share", "分享"),
        )
    }
}
