package com.local.personaltoolbox.modules.recents

/** This allowlist is also enforced inside the shell process, never just in the UI. */
object RecentsTargets {
    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    const val THREADS = "com.instagram.barcelona"
    const val FACEBOOK = "com.facebook.katana"
    const val DCARD = "com.sparkslab.dcardreader"
    const val AOV = "com.garena.game.kgtw"
    val packages = setOf(YOUTUBE, INSTAGRAM, THREADS, FACEBOOK, DCARD, AOV)
}

internal data class RecentCard(
    val taskId: Int,
    val userId: Int,
    val basePackage: String?,
    val realPackage: String?,
    val topPackage: String?,
)

internal object RecentsPolicy {
    fun uniqueCard(cards: List<RecentCard>, target: String, userId: Int): RecentCard? {
        require(target in RecentsTargets.packages && userId >= 0)
        // Never act on a partial or duplicate snapshot, or on a cross-app task.
        require(cards.size < 100 && cards.map { it.taskId }.distinct().size == cards.size)
        val candidates = cards.filter { it.userId == userId &&
            (it.basePackage == target || it.realPackage == target || it.topPackage == target) }
        require(candidates.size <= 1) { "ambiguous_task" }
        return candidates.singleOrNull()?.also {
            require(it.taskId >= 0 && it.basePackage == target && it.realPackage == target &&
                (it.topPackage == null || it.topPackage == target)) { "cross_app_task" }
        }
    }
}

internal enum class RemovalResult {
    REMOVED, ABSENT, NOT_READY, BLOCKED, FAILED;
    val confirmed: Boolean get() = this == REMOVED || this == ABSENT
}

/** One bounded transaction, with fresh identity checks and one postcondition read. */
internal class RecentsRemoval(private val host: Host) {
    interface Host {
        fun cards(): List<RecentCard>
        fun interactiveAndUnlocked(): Boolean
        fun foreground(): Pair<Int, String?>?
        fun now(): Long
        fun confirmDispatch(): Boolean = true
        fun remove(taskId: Int): Boolean
    }

    fun run(target: String, userId: Int, foregroundOnly: Boolean, expiresAt: Long): RemovalResult {
        if (target !in RecentsTargets.packages || expiresAt - host.now() !in 0..5_000) return RemovalResult.BLOCKED
        return try {
            val initial = RecentsPolicy.uniqueCard(host.cards(), target, userId) ?: return RemovalResult.ABSENT
            if (!host.interactiveAndUnlocked() ||
                (foregroundOnly && host.foreground() != (initial.taskId to target))) return RemovalResult.BLOCKED
            if (!host.confirmDispatch()) return RemovalResult.BLOCKED
            // Android 11 getRunningTasks touches lastActiveTime. Only stable task identity belongs
            // in this equality check; the client reauthorizes the foreground visit at dispatch.
            if (RecentsPolicy.uniqueCard(host.cards(), target, userId) != initial || host.now() > expiresAt) return RemovalResult.BLOCKED
            if (!host.interactiveAndUnlocked() ||
                (foregroundOnly && host.foreground() != (initial.taskId to target))) return RemovalResult.BLOCKED
            if (!host.remove(initial.taskId)) return RemovalResult.FAILED
            if (RecentsPolicy.uniqueCard(host.cards(), target, userId) == null) RemovalResult.REMOVED else RemovalResult.FAILED
        } catch (_: Exception) { RemovalResult.FAILED }
    }
}

internal object RecentsProtocol {
    private val noncePattern = Regex("[a-f0-9]{32}")
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    fun validNonce(nonce: String) = noncePattern.matches(nonce)
    fun reply(nonce: String, status: String) = "TBRC1|$nonce|$status"
    fun parse(output: String, nonce: String): String? {
        require(validNonce(nonce))
        val lines = output.lineSequence().map { it.trim() }.filter { it.startsWith("TBRC1|") }.toList()
        if (lines.size != 1) return null
        return lines.single().removePrefix("TBRC1|$nonce|").takeIf {
            lines.single() == reply(nonce, it) && it in setOf("READY", "REMOVED", "ABSENT", "BLOCKED", "FAILED")
        }
    }
}
