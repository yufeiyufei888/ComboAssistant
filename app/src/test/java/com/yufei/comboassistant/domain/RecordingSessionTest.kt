package com.yufei.comboassistant.domain

import com.yufei.comboassistant.testSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionTest {
    @Test
    fun mirrorExecutionTimeIsExcludedFromTimeline() {
        val session = RecordingSession()
        session.start(100L)

        val firstResult = session.prepare(testSegment(durationMs = 100L).strokes, gestureDownElapsedMs = 150L)
        assertTrue(firstResult is PrepareSegmentResult.Ready)
        val first = (firstResult as PrepareSegmentResult.Ready).segment
        session.commit(first, nextReadyAtElapsedMs = 1_000L)
        val secondResult = session.prepare(testSegment(durationMs = 60L).strokes, gestureDownElapsedMs = 1_025L)
        assertTrue(secondResult is PrepareSegmentResult.Ready)
        val second = (secondResult as PrepareSegmentResult.Ready).segment
        session.commit(second, nextReadyAtElapsedMs = 2_000L)

        val timeline = session.finish()
        assertEquals(listOf(50L, 25L), timeline.segments.map { it.gapBeforeMs })
        assertEquals(235L, timeline.durationMs)
    }

    @Test
    fun segmentLimitStopsBeforeExtraGesture() {
        val session = RecordingSession(maxSegments = 2)
        session.start(0L)
        repeat(2) { index ->
            val result = session.prepare(testSegment(0L, 20L).strokes, index * 30L)
            assertTrue(result is PrepareSegmentResult.Ready)
            session.commit((result as PrepareSegmentResult.Ready).segment, (index + 1) * 30L)
        }

        assertTrue(session.prepare(testSegment(0L, 20L).strokes, 100L) is PrepareSegmentResult.LimitReached)
    }

    @Test
    fun durationLimitIncludesOnlyRecordedGapsAndGestures() {
        val session = RecordingSession(maxDurationMs = 100L)
        session.start(0L)

        assertTrue(
            session.prepare(testSegment(durationMs = 81L).strokes, gestureDownElapsedMs = 20L) is
                PrepareSegmentResult.LimitReached,
        )
    }
}
