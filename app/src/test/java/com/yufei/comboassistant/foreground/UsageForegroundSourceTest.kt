package com.yufei.comboassistant.foreground

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageForegroundSourceTest {
    @Test
    fun `usage event keeps its real age in elapsed realtime domain`() {
        assertEquals(
            7_000L,
            AndroidUsageForegroundSource.eventElapsedRealtimeMs(
                nowElapsedRealtimeMs = 10_000L,
                nowWallTimeMs = 100_000L,
                eventWallTimeMs = 97_000L,
                maxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun `future and over-window wall timestamps are safely clamped`() {
        assertEquals(
            10_000L,
            AndroidUsageForegroundSource.eventElapsedRealtimeMs(10_000L, 100_000L, 101_000L, 15_000L),
        )
        assertEquals(
            0L,
            AndroidUsageForegroundSource.eventElapsedRealtimeMs(10_000L, 100_000L, 1L, 15_000L),
        )
    }
}
