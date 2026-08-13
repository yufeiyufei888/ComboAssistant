package com.yufei.comboassistant.service

import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.ScreenOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTrackerTest {
    private val landscape = DisplaySnapshot(2400, 1080, ScreenOrientation.LANDSCAPE)
    private val portrait = DisplaySnapshot(1080, 2400, ScreenOrientation.PORTRAIT)

    @Test
    fun `display only becomes stable after final sample`() {
        val tracker = DisplayTracker(landscape)

        val token = tracker.markUnstable()
        tracker.recordIntermediate(token, portrait)

        assertEquals(DisplayState.Unstable(portrait), tracker.state)
        tracker.recordStable(token, portrait)
        assertEquals(DisplayState.Stable(portrait), tracker.state)
    }

    @Test
    fun `stale configuration callbacks are ignored`() {
        val tracker = DisplayTracker(landscape)
        val staleToken = tracker.markUnstable()
        val activeToken = tracker.markUnstable()

        assertFalse(tracker.recordStable(staleToken, portrait))
        assertTrue(tracker.recordIntermediate(activeToken, landscape))
        assertTrue(tracker.recordStable(activeToken, landscape))
        assertEquals(DisplayState.Stable(landscape), tracker.state)
    }

    @Test
    fun `different intermediate and final samples remain unstable`() {
        val tracker = DisplayTracker(landscape)
        val token = tracker.markUnstable()
        tracker.recordIntermediate(token, portrait)

        assertFalse(tracker.recordStable(token, landscape))
        assertEquals(DisplayState.Unstable(landscape), tracker.state)
    }
}
