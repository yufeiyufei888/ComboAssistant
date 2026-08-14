package com.yufei.comboassistant.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageForegroundSourceTest {
    @Test
    fun `long running game remains current until its activity pauses`() {
        val reducer = UsageForegroundReducer()
        reducer.accept(resumed(GAME, "MatchActivity", 1_000L))

        assertEquals(GAME, reducer.snapshot()?.packageName)
        assertEquals(1_000L, reducer.snapshot()?.stateEventWallTimeMs)

        reducer.accept(paused(GAME, "MatchActivity", 121_000L))
        assertNull(reducer.snapshot())
    }

    @Test
    fun `same package activity handoff remains one foreground package`() {
        val reducer = UsageForegroundReducer()
        reducer.accept(resumed(GAME, "LoginActivity", 100L))
        reducer.accept(resumed(GAME, "MatchActivity", 200L))
        reducer.accept(paused(GAME, "LoginActivity", 250L))

        val snapshot = reducer.snapshot()
        assertEquals(GAME, snapshot?.packageName)
        assertEquals("MatchActivity", snapshot?.className)
    }

    @Test
    fun `real screenshot activity blocks game then restores it after pause`() {
        val reducer = UsageForegroundReducer()
        reducer.accept(resumed(GAME, "MatchActivity", 100L))
        reducer.accept(resumed(SCREENSHOT, "EditorActivity", 200L))
        assertNull(reducer.snapshot()) // Two resumed packages are unsafe to disambiguate.

        reducer.accept(paused(SCREENSHOT, "EditorActivity", 300L))
        assertEquals(GAME, reducer.snapshot()?.packageName)
        assertEquals(300L, reducer.snapshot()?.stateEventWallTimeMs)
    }

    @Test
    fun `legacy package foreground and background events are paired`() {
        val reducer = UsageForegroundReducer()
        reducer.accept(resumed(GAME, null, 100L, ForegroundObservationKind.MOVE_TO_FOREGROUND))
        assertEquals(GAME, reducer.snapshot()?.packageName)

        reducer.accept(paused(GAME, null, 200L, ForegroundObservationKind.MOVE_TO_FOREGROUND))
        assertNull(reducer.snapshot())
    }

    @Test
    fun `different simultaneously resumed packages are ambiguous`() {
        val reducer = UsageForegroundReducer()
        reducer.accept(resumed(GAME, "MatchActivity", 100L))
        reducer.accept(resumed(BROWSER, "BrowserActivity", 200L))

        assertNull(reducer.snapshot())
    }

    @Test
    fun `polling gap or clock rollback forces full reconstruction`() {
        assertEquals(
            false,
            shouldReconstructUsageQuery(true, 120_000L, 100_000L, 60_000L),
        )
        assertEquals(
            true,
            shouldReconstructUsageQuery(true, 170_001L, 100_000L, 60_000L),
        )
        assertEquals(
            true,
            shouldReconstructUsageQuery(true, 90_000L, 100_000L, 60_000L),
        )
        assertEquals(
            true,
            shouldReconstructUsageQuery(false, 120_000L, Long.MIN_VALUE, 60_000L),
        )
    }

    private fun resumed(
        packageName: String,
        className: String?,
        time: Long,
        kind: ForegroundObservationKind = ForegroundObservationKind.ACTIVITY_RESUMED,
    ) = UsageLifecycleRecord(
        packageName = packageName,
        className = className,
        eventWallTimeMs = time,
        transition = UsageLifecycleTransition.FOREGROUND,
        foregroundKind = kind,
    )

    private fun paused(
        packageName: String,
        className: String?,
        time: Long,
        kind: ForegroundObservationKind = ForegroundObservationKind.ACTIVITY_RESUMED,
    ) = UsageLifecycleRecord(
        packageName = packageName,
        className = className,
        eventWallTimeMs = time,
        transition = UsageLifecycleTransition.BACKGROUND,
        foregroundKind = kind,
    )

    private companion object {
        const val GAME = "com.example.game"
        const val SCREENSHOT = "com.miui.screenshot"
        const val BROWSER = "com.example.browser"
    }
}
