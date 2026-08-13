package com.yufei.comboassistant

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.dropbox.dropshots.Dropshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
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
