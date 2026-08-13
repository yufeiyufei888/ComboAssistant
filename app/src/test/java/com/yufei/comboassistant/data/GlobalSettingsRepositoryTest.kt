package com.yufei.comboassistant.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobalSettingsRepositoryTest {
    @Test
    fun enhancedForegroundDetectionDefaultsOffAndPersistsChanges() = runTest {
        val repository = GlobalSettingsRepository(RuntimeEnvironment.getApplication())

        repository.setEnhancedForegroundDetection(false)
        assertFalse(repository.settings.first().enhancedForegroundDetection)

        repository.setEnhancedForegroundDetection(true)
        assertTrue(repository.settings.first().enhancedForegroundDetection)
    }
}
