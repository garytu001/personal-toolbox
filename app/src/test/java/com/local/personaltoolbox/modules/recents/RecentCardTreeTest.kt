package com.local.personaltoolbox.modules.recents

import org.junit.Assert.*
import org.junit.Test

class RecentCardTreeTest {
    private val labels = setOf("YouTube")
    private fun n(id: String = "", text: String = "", desc: String = "", click: Boolean = false,
                  longClick: Boolean = false, visible: Boolean = true, vararg children: RecentNode) =
        RecentNode(id = if (id.isEmpty()) "" else "com.asus.launcher:id/$id", text = text,
            description = desc, clickable = click, longClickable = longClick, fullyVisible = visible,
            children = children.toList())
    private fun paths(node: RecentNode, path: List<Int> = emptyList()): RecentNode =
        node.copy(path = path, children = node.children.mapIndexed { i, child -> paths(child, path + i) })
    private fun card(label: String, visible: Boolean = true) = n("task_view_single", children = arrayOf(
        n("snapshot", desc = label), n("icon", longClick = true, visible = visible)))
    private fun menu(label: String, closeText: String = "Close") = n(children = arrayOf(
        n(text = label), n(text = "App info"), n(click = true, children = arrayOf(n(text = closeText)))))

    @Test fun selectsTargetLogoAmongOtherCardsAndNeverClearAll() {
        val root = paths(n("overview_panel", children = arrayOf(card("Instagram"), card("YouTube"),
            n("action_asus_clear_all", text = "Clear all", click = true))))
        assertEquals(listOf(1, 1), RecentCardTree.icon(root, labels)?.path)
        assertNull(RecentCardTree.close(root, labels))
    }

    @Test fun duplicateTargetCardsAndClippedLogosAreRejected() {
        assertNull(RecentCardTree.icon(paths(n(children = arrayOf(card("YouTube"), card("YouTube")))), labels))
        assertNull(RecentCardTree.icon(paths(n(children = arrayOf(card("YouTube", false)))), labels))
    }

    @Test fun targetMenuSelectsClickableCloseRow() {
        val root = paths(n(children = arrayOf(card("YouTube"), menu("YouTube"))))
        assertEquals(listOf(1, 2), RecentCardTree.close(root, labels)?.path)
    }

    @Test fun backgroundTargetCardCannotAuthorizeAnotherAppsMenu() {
        val root = paths(n(children = arrayOf(card("YouTube"), menu("ChatGPT"))))
        assertNull(RecentCardTree.close(root, labels))
    }

    @Test fun closeAllAndPartialLabelsAreNeverMatched() {
        for (label in listOf("Close all", "Clear all", "Close YouTube", "關閉全部")) {
            assertNull(RecentCardTree.close(paths(menu("YouTube", label)), labels))
        }
        assertNull(RecentCardTree.close(paths(menu("YouTube Music")), labels))
        assertNull(RecentCardTree.icon(paths(card("YouTube Music")), labels))
    }

    @Test fun ambiguousMenusAndDisabledGeometryFailClosed() {
        assertNull(RecentCardTree.close(paths(n(children = arrayOf(menu("YouTube"), menu("YouTube")))), labels))
        val root = paths(menu("YouTube"))
        val clipped = root.copy(children = root.children.map { if (it.clickable) it.copy(fullyVisible = false) else it })
        assertNull(RecentCardTree.close(clipped, labels))
    }

    @Test fun chineseCloseAndResolvedAppLabelsAreSupported() {
        assertNotNull(RecentCardTree.close(paths(menu("傳說對決", "關閉")), setOf("傳說對決")))
        assertEquals(6, RecentApps.labels.size)
        assertFalse(RecentApps.labels.containsValue("ChatGPT"))
    }

    @Test fun missingCardOnUnknownScreenDoesNotConfirmRemoval() {
        assertFalse(RecentCardTree.removed(paths(n()), labels))
        assertFalse(RecentCardTree.removed(paths(n("overview_panel", children = arrayOf(card("YouTube")))), labels))
        assertFalse(RecentCardTree.removed(paths(n("overview_panel", children = arrayOf(menu("YouTube")))), labels))
    }

    @Test fun knownOverviewWithoutTargetOrReturnedHomeConfirmsDisappearance() {
        assertTrue(RecentCardTree.removed(paths(n("overview_panel", children = arrayOf(card("Instagram")))), labels))
        assertTrue(RecentCardTree.removed(paths(n("workspace")), labels))
    }
}
