package com.yufei.comboassistant

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.dropshots.Dropshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchTestDropshotsTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(TouchTestActivity::class.java)

    @get:Rule
    val dropshots = Dropshots()

    @Test
    fun touchFieldOverlayBaseline() {
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "touch_test_field")
        }
    }
}
