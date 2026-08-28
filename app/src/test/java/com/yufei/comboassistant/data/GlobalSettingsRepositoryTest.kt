package com.yufei.comboassistant.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun stopButtonLayoutPersistsAndClampsWithoutChangingDataStoreFile() = runTest {
        val repository = GlobalSettingsRepository(RuntimeEnvironment.getApplication())

        repository.setOverlayLayout(
            ballX = 0.25f,
            ballY = 0.75f,
            stopButtonX = 2f,
            stopButtonY = -1f,
            stopButtonSizeDp = 100f,
        )

        val settings = repository.settings.first()
        assertEquals(0.25f, settings.ballX, 0.0001f)
        assertEquals(0.75f, settings.ballY, 0.0001f)
        assertEquals(1f, settings.stopButtonX, 0.0001f)
        assertEquals(0f, settings.stopButtonY, 0.0001f)
        assertEquals(72f, settings.stopButtonSizeDp, 0.0001f)
    }
}
