package com.yufei.comboassistant.overlay

import com.yufei.comboassistant.testCombo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCoordinatorTest {
    @Test
    fun reconcilesWithoutRecreatingUnchangedEntries() {
        val coordinator = OverlayCoordinator<String, Int>()
        assertEquals(mapOf("a" to 1), coordinator.reconcile(mapOf("a" to 1)).added)

        val unchanged = coordinator.reconcile(mapOf("a" to 1))
        assertTrue(unchanged.added.isEmpty())
        assertTrue(unchanged.updated.isEmpty())
        assertTrue(unchanged.removed.isEmpty())

        val changed = coordinator.reconcile(mapOf("a" to 2, "b" to 3))
        assertEquals(mapOf("a" to 2), changed.updated)
        assertEquals(mapOf("b" to 3), changed.added)
        assertTrue(changed.removed.isEmpty())

        assertEquals(setOf("a"), coordinator.reconcile(mapOf("b" to 3)).removed)
    }

    @Test
    fun failedWindowOperationsAreRetriedUntilAcknowledged() {
        val coordinator = OverlayCoordinator<String, Int>()
        var addAttempts = 0
        coordinator.reconcile(
            desired = mapOf("a" to 1),
            onAdded = { _, _ -> ++addAttempts > 1 },
        )
        coordinator.reconcile(
            desired = mapOf("a" to 1),
            onAdded = { _, _ -> ++addAttempts > 1 },
        )
        assertEquals(2, addAttempts)
        assertTrue(coordinator.reconcile(mapOf("a" to 1)).added.isEmpty())

        var updateAttempts = 0
        coordinator.reconcile(
            desired = mapOf("a" to 2),
            onUpdated = { _, _ -> ++updateAttempts > 1 },
        )
        coordinator.reconcile(
            desired = mapOf("a" to 2),
            onUpdated = { _, _ -> ++updateAttempts > 1 },
        )
        assertEquals(2, updateAttempts)
        assertTrue(coordinator.reconcile(mapOf("a" to 2)).updated.isEmpty())

        var removeAttempts = 0
        coordinator.reconcile(
            desired = emptyMap(),
            onRemoved = { ++removeAttempts > 1 },
        )
        coordinator.reconcile(
            desired = emptyMap(),
            onRemoved = { ++removeAttempts > 1 },
        )
        assertEquals(2, removeAttempts)
        assertTrue(coordinator.reconcile(emptyMap()).removed.isEmpty())
    }

    @Test
    fun callbackExceptionDoesNotEscapeAndOperationCanRetry() {
        val coordinator = OverlayCoordinator<String, Int>()

        coordinator.reconcile(
            desired = mapOf("a" to 1),
            onAdded = { _, _ -> error("transient WindowManager failure") },
        )

        assertEquals(
            mapOf("a" to 1),
            coordinator.reconcile(mapOf("a" to 1)).added,
        )
    }

    @Test
    fun layoutSessionCommitsWorkingCopyOrReturnsOriginals() {
        val original = testCombo()
        val session = LayoutSession(listOf(original), FloatingBallPosition(0.1f, 0.2f))
        session.moveCombo(original.id, 0.3f, 0.4f)
        session.resizeComboKeepingCenter(original.id, 20f, 1000, 500, 1f)
        session.setOpacity(original.id, 2f)
        session.moveBall(3f, -1f)

        val committed = session.committed(123L).single()
        // Resizing preserves the center, so the normalized top-left changes slightly.
        assertEquals(0.30168068f, committed.buttonX, 0.0001f)
        assertEquals(0.40176994f, committed.buttonY, 0.0001f)
        assertEquals(36f, committed.buttonSizeDp)
        assertEquals(1f, committed.buttonOpacity)
        assertEquals(123L, committed.updatedAt)
        assertEquals(FloatingBallPosition(1f, 0f), session.ballPosition())

        assertEquals(original, session.cancelledCombos().single())
        assertEquals(FloatingBallPosition(0.1f, 0.2f), session.cancelledBall())
    }
}
