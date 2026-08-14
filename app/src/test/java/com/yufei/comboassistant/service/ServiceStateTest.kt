package com.yufei.comboassistant.service

import com.yufei.comboassistant.foreground.ConfirmedForegroundPackage
import com.yufei.comboassistant.foreground.ForegroundConfirmationMethod
import com.yufei.comboassistant.foreground.ForegroundSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateTest {
    private fun confirmed(packageName: String) = ForegroundSessionState.Confirmed(
        ConfirmedForegroundPackage(
            packageName = packageName,
            confirmedAtElapsedRealtimeMs = 1L,
            lastObservedAtElapsedRealtimeMs = 1L,
            method = ForegroundConfirmationMethod.USAGE_STATS,
        ),
    )

    @Test
    fun `direct usage confirmation of another app stops guarded session`() {
        assertTrue(
            isRealForegroundSwitch(
                previous = confirmed("game.package"),
                current = confirmed("browser.package"),
                guardedTargets = listOf("game.package"),
            ),
        )
    }

    @Test
    fun `same package refresh does not stop guarded session`() {
        assertFalse(
            isRealForegroundSwitch(
                previous = confirmed("game.package"),
                current = confirmed("game.package"),
                guardedTargets = listOf("game.package"),
            ),
        )
    }

    @Test
    fun `trusted temporary window only obscures guarded session`() {
        val game = confirmed("game.package")
        assertFalse(
            isRealForegroundSwitch(
                previous = game,
                current = ForegroundSessionState.TemporarilyObscured(
                    obscuringPackageName = "ime.package",
                    confirmation = game.confirmation,
                    observedAtElapsedRealtimeMs = 2L,
                ),
                guardedTargets = listOf("game.package"),
            ),
        )
    }

    @Test
    fun `layout commit is single flight and user cancel cannot race it`() {
        val guard = LayoutCommitGuard()
        guard.begin("layout-1")

        assertTrue(guard.tryStart("layout-1"))
        assertFalse(guard.tryStart("layout-1"))
        assertFalse(guard.cancel(force = false))
        assertTrue(guard.isSaving("layout-1"))
        assertTrue(guard.complete("layout-1"))
        assertFalse(guard.isSaving("layout-1"))
    }

    @Test
    fun `forced lifecycle cancel rejects stale layout completion`() {
        val guard = LayoutCommitGuard()
        guard.begin("layout-1")
        assertTrue(guard.tryStart("layout-1"))

        assertTrue(guard.cancel(force = true))
        assertFalse(guard.complete("layout-1"))

        guard.begin("layout-2")
        assertTrue(guard.tryStart("layout-2"))
        assertTrue(guard.fail("layout-2"))
        assertTrue(guard.tryStart("layout-2"))
    }

    @Test
    fun `own overlay content event is ignored without losing debug test activity`() {
        val decision = ownPackageEventDecision(
            isWindowStateChanged = false,
            className = "android.widget.TextView",
            mainActivityClassName = "MainActivity",
            touchTestActivityClassName = "TouchTestActivity",
            debugBuild = true,
            wasDebugTouchTestVisible = true,
        )

        assertFalse(decision.shouldProcess)
        assertTrue(decision.debugTouchTestVisible)
    }

    @Test
    fun `only activity state changes alter own package foreground routing`() {
        val testActivity = ownPackageEventDecision(
            isWindowStateChanged = true,
            className = "TouchTestActivity",
            mainActivityClassName = "MainActivity",
            touchTestActivityClassName = "TouchTestActivity",
            debugBuild = true,
            wasDebugTouchTestVisible = false,
        )
        assertTrue(testActivity.shouldProcess)
        assertTrue(testActivity.debugTouchTestVisible)

        val mainActivity = ownPackageEventDecision(
            isWindowStateChanged = true,
            className = "MainActivity",
            mainActivityClassName = "MainActivity",
            touchTestActivityClassName = "TouchTestActivity",
            debugBuild = true,
            wasDebugTouchTestVisible = true,
        )
        assertTrue(mainActivity.shouldProcess)
        assertFalse(mainActivity.debugTouchTestVisible)
    }

    @Test
    fun `ball drag distinguishes click commit and cancel rollback`() {
        val drag = BallDragState(touchSlopPx = 8)
        drag.begin(rawX = 100f, rawY = 100f, x = 20, y = 30)
        assertEquals(BallDragResult.None, drag.move(105f, 104f, maxX = 300, maxY = 200))
        assertEquals(BallDragResult.Click, drag.finish(currentX = 20, currentY = 30))

        drag.begin(rawX = 100f, rawY = 100f, x = 20, y = 30)
        assertEquals(
            BallDragResult.Position(70, 90),
            drag.move(150f, 160f, maxX = 300, maxY = 200),
        )
        assertEquals(BallDragResult.Position(70, 90), drag.finish(currentX = 70, currentY = 90))

        drag.begin(rawX = 150f, rawY = 160f, x = 70, y = 90)
        drag.move(220f, 220f, maxX = 300, maxY = 200)
        assertEquals(BallDragResult.Position(70, 90), drag.cancel())
    }

    @Test
    fun `landscape panel is capped at half display height`() {
        assertEquals(500, panelHeightPx(1_000, 900, landscape = true))
        assertEquals(300, panelHeightPx(1_000, 300, landscape = true))
        assertEquals(750, panelHeightPx(1_000, 900, landscape = false))
    }

    @Test
    fun `panel rendering follows requested state rather than stale physical view`() {
        assertFalse(shouldRenderPanel(requestedOpen = false, attached = false))
        assertFalse(shouldRenderPanel(requestedOpen = false, attached = true))
        assertTrue(shouldRenderPanel(requestedOpen = true, attached = false))
        assertFalse(shouldRenderPanel(requestedOpen = true, attached = true))

        assertFalse(shouldRedrawLayoutPanel(requestedOpen = false, selectionChanged = true))
        assertFalse(shouldRedrawLayoutPanel(requestedOpen = true, selectionChanged = false))
        assertTrue(shouldRedrawLayoutPanel(requestedOpen = true, selectionChanged = true))
    }
}
