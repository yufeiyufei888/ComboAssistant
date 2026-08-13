package com.yufei.comboassistant.playback

import com.yufei.comboassistant.domain.DisplaySnapshot
import com.yufei.comboassistant.domain.GestureSegment
import com.yufei.comboassistant.domain.ScreenOrientation
import com.yufei.comboassistant.testCombo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {
    private val landscape = DisplaySnapshot(2400, 1080, ScreenOrientation.LANDSCAPE)
    private fun allowedGate(display: DisplaySnapshot = landscape) =
        ExecutionGate { ExecutionGateResult.Allowed(display) }

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
        var reason = "当前应用与连招绑定游戏不一致"
        val engine = PlaybackEngine(this, performer) { ExecutionGateResult.Blocked(reason) }

        assertFalse(engine.play(testCombo()))
        assertTrue(engine.state.value is PlaybackState.Failed)
        reason = "屏幕方向与录制方向不一致"
        assertFalse(engine.play(testCombo()))
        assertEquals(0, performer.count)
    }

    @Test
    fun completesOneAndNineHundredNinetyNineRepeatsExactly() = runTest {
        listOf(1, 999).forEach { repeatCount ->
            val performer = CountingPerformer()
            val engine = PlaybackEngine(this, performer, allowedGate())
            assertTrue(engine.play(testCombo(repeatCount = repeatCount, repeatIntervalMs = 50L)))
            advanceUntilIdle()
            assertEquals(repeatCount, performer.count)
            assertEquals(PlaybackState.Idle, engine.state.value)
        }
    }

    @Test
    fun serializesPlaybackAndCanStop() = runTest {
        val performer = CountingPerformer(result = GestureResult.CANCELLED)
        val engine = PlaybackEngine(this, performer, allowedGate())
        val combo = testCombo(repeatCount = 2)

        assertTrue(engine.play(combo))
        assertFalse(engine.play(combo))
        advanceUntilIdle()
        assertTrue(engine.state.value is PlaybackState.Failed)
    }

    @Test
    fun checksGateBeforeEverySegment() = runTest {
        val performer = CountingPerformer()
        var checks = 0
        val engine = PlaybackEngine(this, performer) {
            checks += 1
            if (checks <= 2) ExecutionGateResult.Allowed(landscape)
            else ExecutionGateResult.Blocked("已离开目标游戏")
        }
        val combo = testCombo().let { value ->
            value.copy(timeline = value.timeline.copy(segments = value.timeline.segments + value.timeline.segments))
        }

        assertTrue(engine.play(combo))
        advanceUntilIdle()

        assertEquals(1, performer.count)
        assertEquals(PlaybackState.Failed("已离开目标游戏"), engine.state.value)
    }

    @Test
    fun stopCancelsActivePerformer() = runTest {
        val performer = CountingPerformer()
        val engine = PlaybackEngine(this, performer, allowedGate())
        val combo = testCombo(repeatCount = 999, repeatIntervalMs = 10_000L)

        assertTrue(engine.play(combo))
        engine.stop("用户停止")

        assertEquals(1, performer.cancelCount)
        assertEquals(PlaybackState.Stopped("用户停止"), engine.state.value)
    }

    @Test
    fun stopRejectsReplacementUntilCancelledJobFinishesCleanup() = runTest {
        val performer = SuspendingPerformer()
        val engine = PlaybackEngine(this, performer, allowedGate())
        val combo = testCombo()

        assertTrue(engine.play(combo))
        runCurrent()
        engine.stop("用户停止")

        assertFalse(engine.play(combo))
        runCurrent()
        assertTrue(engine.play(combo))
        engine.stop("测试结束")
        advanceUntilIdle()
    }

    @Test
    fun stopBeforeFirstDispatchStillRejectsImmediateReplacement() = runTest {
        val performer = CountingPerformer()
        val engine = PlaybackEngine(this, performer, allowedGate())
        val combo = testCombo()

        assertTrue(engine.play(combo))
        // The first lazy playback coroutine has not run yet, but it still owns the engine slot.
        engine.stop("首段调度前停止")

        assertFalse(engine.play(combo))
        assertEquals(1, performer.cancelCount)
        assertEquals(0, performer.count)

        runCurrent()
        assertTrue(engine.play(combo))
        advanceUntilIdle()
        assertEquals(1, performer.count)
    }

    private class CountingPerformer(
        private val result: GestureResult = GestureResult.COMPLETED,
    ) : GesturePerformer {
        var count: Int = 0
        var cancelCount: Int = 0
        override suspend fun perform(
            segment: GestureSegment,
            speed: Float,
            display: DisplaySnapshot,
        ): GestureResult {
            count += 1
            return result
        }

        override fun cancelActive() {
            cancelCount += 1
        }
    }

    private class SuspendingPerformer : GesturePerformer {
        override suspend fun perform(
            segment: GestureSegment,
            speed: Float,
            display: DisplaySnapshot,
        ): GestureResult = awaitCancellation()

        override fun cancelActive() = Unit
    }
}
