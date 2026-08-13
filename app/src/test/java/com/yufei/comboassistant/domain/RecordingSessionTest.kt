package com.yufei.comboassistant.domain

import com.yufei.comboassistant.testSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionTest {
    @Test
    fun continuousRecordingStoresOnlyWaitBetweenCompletedGestures() {
        val session = RecordingSession()
        session.start(startedAtUptimeMs = 100L)

        val first = session.append(
            strokes = testSegment(durationMs = 100L).strokes,
            gestureDownUptimeMs = 150L,
            gestureUpUptimeMs = 250L,
        )
        val second = session.append(
            strokes = testSegment(durationMs = 60L).strokes,
            gestureDownUptimeMs = 1_000L,
            gestureUpUptimeMs = 1_060L,
        )

        assertTrue(first is AppendSegmentResult.Appended)
        assertTrue(second is AppendSegmentResult.Appended)
        // A long wait before finish is observable to the session clock but is not in playback.
        assertEquals(4_900L, session.elapsedDurationMs(atUptimeMs = 5_000L))
        val timeline = session.finish()
        assertEquals(listOf(0L, 750L), timeline.segments.map { it.gapBeforeMs })
        assertEquals(910L, timeline.durationMs)
        assertFalse(session.isActive)
    }

    @Test
    fun durationLimitCountsIdleBeforeFirstGestureButDoesNotPersistIt() {
        val session = RecordingSession(maxDurationMs = 100L)
        session.start(startedAtUptimeMs = 1_000L)

        val result = session.append(
            strokes = testSegment(durationMs = 20L).strokes,
            gestureDownUptimeMs = 1_080L,
            gestureUpUptimeMs = 1_100L,
        )

        assertTrue(result is AppendSegmentResult.Appended)
        result as AppendSegmentResult.Appended
        assertEquals(RecordingLimit.DURATION, result.reachedLimit)
        assertEquals(0L, result.segment.gapBeforeMs)
        assertEquals(20L, session.finish().durationMs)
    }

    @Test
    fun gestureCrossingDurationLimitIsDiscarded() {
        val session = RecordingSession(maxDurationMs = 100L)
        session.start(startedAtUptimeMs = 1_000L)

        val result = session.append(
            strokes = testSegment(durationMs = 21L).strokes,
            gestureDownUptimeMs = 1_080L,
            gestureUpUptimeMs = 1_101L,
        )

        assertTrue(result is AppendSegmentResult.LimitReached)
        assertEquals(RecordingLimit.DURATION, (result as AppendSegmentResult.LimitReached).limit)
        assertEquals(0, session.segmentCount)
        assertTrue(session.finish().segments.isEmpty())
    }

    @Test
    fun twoHundredthGestureIsAcceptedAndFurtherGestureIsRejected() {
        val session = RecordingSession()
        session.start(startedAtUptimeMs = 0L)

        var finalAccepted: AppendSegmentResult.Appended? = null
        repeat(MAX_GESTURE_SEGMENTS) { index ->
            val down = index * 10L
            val result = session.append(
                strokes = testSegment(durationMs = 5L).strokes,
                gestureDownUptimeMs = down,
                gestureUpUptimeMs = down + 5L,
            )
            assertTrue(result is AppendSegmentResult.Appended)
            finalAccepted = result as AppendSegmentResult.Appended
        }

        assertEquals(RecordingLimit.SEGMENT_COUNT, finalAccepted?.reachedLimit)
        val extra = session.append(
            strokes = testSegment(durationMs = 5L).strokes,
            gestureDownUptimeMs = 2_000L,
            gestureUpUptimeMs = 2_005L,
        )
        assertTrue(extra is AppendSegmentResult.LimitReached)
        assertEquals(RecordingLimit.SEGMENT_COUNT, (extra as AppendSegmentResult.LimitReached).limit)
        assertEquals(MAX_GESTURE_SEGMENTS, session.finish().segments.size)
    }

    @Test
    fun appendRejectsOverlappingOrIncompleteInput() {
        val session = RecordingSession()
        assertTrue(
            session.append(testSegment(durationMs = 10L).strokes, 0L, 10L) is
                AppendSegmentResult.Invalid,
        )
        session.start(startedAtUptimeMs = 100L)
        assertTrue(session.append(emptyList(), 100L, 110L) is AppendSegmentResult.Invalid)
        assertTrue(
            session.append(testSegment(durationMs = 10L).strokes, 110L, 109L) is
                AppendSegmentResult.Invalid,
        )
        assertTrue(
            session.append(testSegment(durationMs = 10L).strokes, 110L, 120L) is
                AppendSegmentResult.Appended,
        )
        assertTrue(
            session.append(testSegment(durationMs = 10L).strokes, 119L, 129L) is
                AppendSegmentResult.Invalid,
        )
    }

    @Test
    fun appendRejectsNonFiniteOrOutOfBoundsSamples() {
        val session = RecordingSession().apply { start(0L) }
        val base = testSegment(durationMs = 10L).strokes.single()

        val nonFinite = base.copy(samples = base.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(x = Float.NaN) else sample
        })
        val outOfBounds = base.copy(samples = base.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(y = 1.1f) else sample
        })

        assertTrue(session.append(listOf(nonFinite), 10L, 20L) is AppendSegmentResult.Invalid)
        assertTrue(session.append(listOf(outOfBounds), 10L, 20L) is AppendSegmentResult.Invalid)
        assertEquals(0, session.segmentCount)
    }

    @Test
    fun cancelDiscardsCompletedGesturesAndStopsSession() {
        val session = RecordingSession()
        session.start(startedAtUptimeMs = 100L)
        session.append(testSegment(durationMs = 10L).strokes, 110L, 120L)

        session.cancel()

        assertFalse(session.isActive)
        assertEquals(0, session.segmentCount)
        assertEquals(0L, session.durationMs)
        assertEquals(0L, session.elapsedDurationMs(1_000L))
        assertEquals(session.maxDurationMs, session.remainingDurationMs(1_000L))
        assertTrue(
            session.append(testSegment(durationMs = 10L).strokes, 130L, 140L) is
                AppendSegmentResult.Invalid,
        )
    }

    @Test
    fun appendedResultHasNoLimitBeforeBoundary() {
        val session = RecordingSession(maxDurationMs = 1_000L, maxSegments = 2)
        session.start(0L)

        val result = session.append(testSegment(durationMs = 10L).strokes, 10L, 20L)

        assertTrue(result is AppendSegmentResult.Appended)
        assertNull((result as AppendSegmentResult.Appended).reachedLimit)
        assertEquals(980L, session.remainingDurationMs(20L))
    }

    @Test
    fun finishingEmptyRecordingProducesEmptyTimelineAndStopsSession() {
        val session = RecordingSession()
        session.start(10_000L)

        val timeline = session.finish()

        assertTrue(timeline.segments.isEmpty())
        assertEquals(0L, timeline.durationMs)
        assertFalse(session.isActive)
    }

    @Test
    fun exactSixtySecondBoundaryIsAcceptedButNextGestureIsDiscarded() {
        val session = RecordingSession()
        session.start(0L)

        val boundary = session.append(
            strokes = testSegment(durationMs = 100L).strokes,
            gestureDownUptimeMs = 59_900L,
            gestureUpUptimeMs = 60_000L,
        )
        val afterBoundary = session.append(
            strokes = testSegment(durationMs = 1L).strokes,
            gestureDownUptimeMs = 60_000L,
            gestureUpUptimeMs = 60_001L,
        )

        assertTrue(boundary is AppendSegmentResult.Appended)
        assertEquals(
            RecordingLimit.DURATION,
            (boundary as AppendSegmentResult.Appended).reachedLimit,
        )
        assertTrue(afterBoundary is AppendSegmentResult.LimitReached)
        assertEquals(1, session.finish().segments.size)
    }

    @Test
    fun newSessionCannotMutatePreviouslyFinishedTimeline() {
        val session = RecordingSession()
        session.start(100L)
        session.append(testSegment(durationMs = 10L).strokes, 110L, 120L)
        val firstTimeline = session.finish()

        session.start(1_000L)
        session.append(testSegment(durationMs = 20L).strokes, 1_010L, 1_030L)
        val secondTimeline = session.finish()

        assertEquals(10L, firstTimeline.segments.single().durationMs)
        assertEquals(20L, secondTimeline.segments.single().durationMs)
        assertEquals(1, firstTimeline.segments.size)
        assertEquals(1, secondTimeline.segments.size)
    }
}
