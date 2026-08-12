package com.yufei.comboassistant.playback

import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import com.yufei.comboassistant.domain.ScreenOrientation
import com.yufei.comboassistant.testCombo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {
    private val landscape = DisplaySnapshot(2400, 1080, ScreenOrientation.LANDSCAPE)

    @Test
    fun scalesDelayAndClampsGestureDuration() {
        assertEquals(400L, PlaybackEngine.scaleDelay(100L, 0.25f))
        assertEquals(25L, PlaybackEngine.scaleDelay(100L, 4f))
        assertEquals(16L, PlaybackEngine.scaleStrokeDuration(1L, 4f))
        assertEquals(400L, PlaybackEngine.scaleStrokeDuration(100L, 0.25f))
    }

    @Test
    fun refusesWrongPackageAndOrientation() = runTest {
        val performer = CountingPerformer()
        val engine = PlaybackEngine(this, performer)

        assertFalse(engine.play(testCombo(), landscape, "other.package"))
        assertTrue(engine.state.value is PlaybackState.Failed)
        assertFalse(
            engine.play(
                testCombo(),
                landscape.copy(orientation = ScreenOrientation.PORTRAIT),
                "com.example.game",
            ),
        )
        assertEquals(0, performer.count)
    }

    @Test
    fun completesOneAndNineHundredNinetyNineRepeatsExactly() = runTest {
        listOf(1, 999).forEach { repeatCount ->
            val performer = CountingPerformer()
            val engine = PlaybackEngine(this, performer)
            assertTrue(
                engine.play(
                    testCombo(repeatCount = repeatCount, repeatIntervalMs = 50L),
                    landscape,
                    "com.example.game",
                ),
            )
            advanceUntilIdle()
            assertEquals(repeatCount, performer.count)
            assertEquals(PlaybackState.Idle, engine.state.value)
        }
    }

    @Test
    fun serializesPlaybackAndCanStop() = runTest {
        val performer = CountingPerformer(result = GestureResult.CANCELLED)
        val engine = PlaybackEngine(this, performer)
        val combo = testCombo(repeatCount = 2)

        assertTrue(engine.play(combo, landscape, "com.example.game"))
        assertFalse(engine.play(combo, landscape, "com.example.game"))
        advanceUntilIdle()
        assertTrue(engine.state.value is PlaybackState.Failed)
    }

    private class CountingPerformer(
        private val result: GestureResult = GestureResult.COMPLETED,
    ) : GesturePerformer {
        var count: Int = 0
        override suspend fun perform(
            segment: GestureSegment,
            speed: Float,
            display: DisplaySnapshot,
        ): GestureResult {
            count += 1
            return result
        }

        override fun cancelActive() = Unit
    }
}
