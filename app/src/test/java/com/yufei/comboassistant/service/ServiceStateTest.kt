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
}
