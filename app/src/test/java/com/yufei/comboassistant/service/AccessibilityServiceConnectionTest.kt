package com.yufei.comboassistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceConnectionTest {
    @Test
    fun connectionFollowsSuccessfulBindAndUnbindLifecycle() {
        val tracker = AccessibilityServiceConnectionTracker()

        assertFalse(tracker.connected.value)
        tracker.markConnected()
        assertTrue(tracker.connected.value)
        tracker.markDisconnected()
        assertFalse(tracker.connected.value)
    }
}
