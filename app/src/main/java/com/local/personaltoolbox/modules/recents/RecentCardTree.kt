package com.local.personaltoolbox.modules.recents

/** Immutable launcher metadata only; never retains AccessibilityNodeInfo or app snapshots. */
internal data class RecentNode(
    val path: List<Int> = emptyList(), val id: String = "", val text: String = "",
    val description: String = "", val clickable: Boolean = false,
    val longClickable: Boolean = false, val fullyVisible: Boolean = true,
    val children: List<RecentNode> = emptyList(),
) {
    fun flatten(): List<RecentNode> = listOf(this) + children.flatMap { it.flatten() }
}

internal object RecentCardTree {
    private const val PREFIX = "com.asus.launcher:id/"
    val closeLabels = setOf("Close", "關閉", "关闭")
    private val infoLabels = setOf("App info", "應用程式資訊", "應用程式信息", "应用信息")
    fun isId(node: RecentNode, id: String) = node.id == PREFIX + id
    fun cards(root: RecentNode, labels: Set<String>) = root.flatten().filter { card ->
        isId(card, "task_view_single") && card.flatten().any {
            isId(it, "snapshot") && it.description in labels
        }
    }
    fun overview(root: RecentNode) = root.flatten().any { isId(it, "overview_panel") }
    fun home(root: RecentNode) = !overview(root) && root.flatten().any { isId(it, "workspace") }
    fun removed(root: RecentNode, labels: Set<String>) = close(root, labels) == null &&
        (home(root) || (overview(root) && cards(root, labels).isEmpty()))
    fun icon(root: RecentNode, labels: Set<String>): RecentNode? {
        val card = cards(root, labels).singleOrNull() ?: return null
        return card.flatten().filter { isId(it, "icon") && it.longClickable && it.fullyVisible }.singleOrNull()
    }

    fun close(root: RecentNode, labels: Set<String>): RecentNode? {
        val matches = mutableListOf<RecentNode>()
        fun visit(node: RecentNode, ancestors: List<RecentNode>) {
            if (node.text in closeLabels || node.description in closeLabels) {
                // A menu must identify its own app, independently of the card underneath it.
                val menu = ancestors.asReversed().firstOrNull { parent ->
                    val nodes = parent.flatten()
                    nodes.none { isId(it, "snapshot") || isId(it, "task_view_single") } &&
                        nodes.any { it.text in labels || it.description in labels } &&
                        nodes.any { it.text in infoLabels || it.description in infoLabels }
                }
                if (menu != null) {
                    val action = (ancestors + node).asReversed().takeWhile { it.path.size >= menu.path.size }
                        .firstOrNull { it.clickable && it.fullyVisible }
                    if (action != null) matches += action
                }
            }
            node.children.forEach { visit(it, ancestors + node) }
        }
        visit(root, emptyList())
        return matches.distinctBy { it.path }.singleOrNull()
    }
}
