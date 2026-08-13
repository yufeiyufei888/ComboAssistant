package com.yufei.comboassistant.service

import com.yufei.comboassistant.foreground.ConfirmedForegroundPackage
import com.yufei.comboassistant.foreground.ForegroundConfirmationMethod
import com.yufei.comboassistant.foreground.ForegroundSessionState
import org.junit.Assert.assertFalse
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
}
