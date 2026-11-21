package com.local.personaltoolbox.modules.recents

import org.junit.Assert.*
import org.junit.Test

class RecentsRemovalTest {
    private val target = RecentsTargets.AOV
    private val game = RecentCard(42, 0, target, target, target)
    private val other = RecentCard(71, 0, "com.other.app", "com.other.app", "com.other.app")
    private inner class Host : RecentsRemoval.Host {
        var snapshot = listOf(game, other)
        var readCount = 0
        var onRead: (Int) -> Unit = {}
        var unlocked = true
        var top: Pair<Int, String?>? = game.taskId to target
        var time = 1_000L
        var accepted = true
        var actuallyRemoves = true
        var authorized = true
        val removed = mutableListOf<Int>()
        override fun cards(): List<RecentCard> { onRead(++readCount); return snapshot }
        override fun interactiveAndUnlocked() = unlocked
        override fun foreground() = top
        override fun now() = time
        override fun confirmDispatch() = authorized
        override fun remove(taskId: Int): Boolean {
            removed += taskId
            if (accepted && actuallyRemoves) snapshot = snapshot.filterNot { it.taskId == taskId }
            return accepted
        }
        fun run(foregroundOnly: Boolean = false) = RecentsRemoval(this).run(target, 0, foregroundOnly, 6_000)
    }

    @Test fun removesOnlyTheUniqueTargetAndReadsPostconditionOnce() {
        val host = Host()
        assertEquals(RemovalResult.REMOVED, host.run())
        assertEquals(listOf(42), host.removed)
        assertEquals(listOf(other), host.snapshot)
        assertEquals(3, host.readCount)
    }
    @Test fun alreadyAbsentIsIdempotentAndNeverSendsARemoval() {
        val host = Host().apply { snapshot = listOf(other) }
        assertEquals(RemovalResult.ABSENT, host.run())
        assertTrue(host.removed.isEmpty())
    }
    @Test fun ambiguousCardsAreNotRemovedAndAreNotRetried() {
        val host = Host().apply { snapshot = listOf(game, game.copy(taskId = 43), other) }
        assertEquals(RemovalResult.FAILED, host.run())
        assertTrue(host.removed.isEmpty())
        assertEquals(1, host.readCount)
    }
    @Test fun refusesACrossAppTaskEvenIfItsTopActivityIsTheTarget() {
        val host = Host().apply { snapshot = listOf(other.copy(topPackage = target)) }
        assertEquals(RemovalResult.FAILED, host.run())
        assertTrue(host.removed.isEmpty())
    }
    @Test fun refusesAChangedBaseOrRealPackage() {
        for (card in listOf(game.copy(realPackage = "com.other.app"), game.copy(basePackage = "com.other.app"), game.copy(topPackage = "com.other.app"))) {
            val host = Host().apply { snapshot = listOf(card) }
            assertEquals(RemovalResult.FAILED, host.run())
            assertTrue(host.removed.isEmpty())
        }
    }
    @Test fun cardsInAnotherUserAreNeverTouched() {
        val host = Host().apply { snapshot = listOf(game.copy(userId = 10), other) }
        assertEquals(RemovalResult.ABSENT, host.run())
        assertTrue(host.removed.isEmpty())
    }
    @Test fun missingTopActivityOnInactiveCardIsAllowedButBaseMustMatch() {
        val host = Host().apply { snapshot = listOf(game.copy(topPackage = null), other) }
        assertEquals(RemovalResult.REMOVED, host.run())
        assertEquals(listOf(42), host.removed)
    }
    @Test fun partialOrDuplicateSnapshotStopsBeforeRemoval() {
        for (cards in listOf(List(100) { game.copy(taskId = it) }, listOf(game, game))) {
            val host = Host().apply { snapshot = cards }
            assertEquals(RemovalResult.FAILED, host.run())
            assertTrue(host.removed.isEmpty())
        }
    }
    @Test fun expiredOrNonAllowlistedRequestNeverEvenReadsTasks() {
        val host = Host()
        assertEquals(RemovalResult.BLOCKED, RecentsRemoval(host).run("com.other.app", 0, false, 6_000))
        assertEquals(RemovalResult.BLOCKED, RecentsRemoval(host).run(target, 0, false, 999))
        assertEquals(RemovalResult.BLOCKED, RecentsRemoval(host).run(target, 0, false, 6_001))
        assertEquals(0, host.readCount)
        assertTrue(host.removed.isEmpty())
    }
    @Test fun lockScreenOrWrongForegroundPreventsCountdownRemoval() {
        val host = Host().apply { unlocked = false }
        assertEquals(RemovalResult.BLOCKED, host.run(true))
        host.unlocked = true
        host.top = other.taskId to other.topPackage
        assertEquals(RemovalResult.BLOCKED, host.run(true))
        assertTrue(host.removed.isEmpty())
    }
    @Test fun taskIdOrIdentityChangesDuringValidationAbortTheOperation() {
        val host = Host().apply { onRead = { count -> if (count == 2) snapshot = listOf(game.copy(taskId = 99), other) } }
        assertEquals(RemovalResult.BLOCKED, host.run())
        assertTrue(host.removed.isEmpty())
    }
    @Test fun clientCancellationBeforeCommitPreventsRemoval() {
        val host = Host().apply { authorized = false }
        assertEquals(RemovalResult.BLOCKED, host.run(true))
        assertTrue(host.removed.isEmpty())
    }
    @Test fun leavingOrLockingDuringLastSnapshotAbortsBeforeRemoval() {
        val host = Host().apply { onRead = { count -> if (count == 2) top = other.taskId to other.topPackage } }
        assertEquals(RemovalResult.BLOCKED, host.run(true))
        assertTrue(host.removed.isEmpty())
        val locked = Host().apply { onRead = { count -> if (count == 2) unlocked = false } }
        assertEquals(RemovalResult.BLOCKED, locked.run())
        assertTrue(locked.removed.isEmpty())
    }
    @Test fun deadlineExpiringDuringValidationAbortsBeforeRemoval() {
        val host = Host().apply { onRead = { count -> if (count == 2) time = 6_001 } }
        assertEquals(RemovalResult.BLOCKED, host.run())
        assertTrue(host.removed.isEmpty())
    }
    @Test fun rejectedOrUnverifiedRemovalIsNotReportedAsSuccessOrRetried() {
        val rejected = Host().apply { accepted = false }
        assertEquals(RemovalResult.FAILED, rejected.run())
        assertEquals(listOf(42), rejected.removed)
        val ignored = Host().apply { actuallyRemoves = false }
        assertEquals(RemovalResult.FAILED, ignored.run())
        assertEquals(listOf(42), ignored.removed)
        assertEquals(3, ignored.readCount)
    }
    @Test fun aovBackgroundCleanupCanRemoveItsCardWhileAnotherAppIsForeground() {
        val host = Host().apply { top = other.taskId to other.topPackage }
        assertEquals(RemovalResult.REMOVED, host.run())
        assertEquals(listOf(other), host.snapshot)
    }
}
