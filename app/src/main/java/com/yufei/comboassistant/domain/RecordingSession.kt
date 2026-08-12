package com.yufei.comboassistant.domain

sealed interface PrepareSegmentResult {
    data class Ready(val segment: GestureSegment) : PrepareSegmentResult
    data class LimitReached(val message: String) : PrepareSegmentResult
    data class Invalid(val message: String) : PrepareSegmentResult
}

class RecordingSession(
    private val maxDurationMs: Long = MAX_RECORDING_DURATION_MS,
    private val maxSegments: Int = MAX_GESTURE_SEGMENTS,
) {
    private val committedSegments = mutableListOf<GestureSegment>()
    private var readyAtMs: Long = 0L
    private var started = false

    val segmentCount: Int get() = committedSegments.size
    val durationMs: Long get() = committedSegments.sumOf { it.gapBeforeMs + it.durationMs }

    fun start(readyAtElapsedMs: Long) {
        committedSegments.clear()
        readyAtMs = readyAtElapsedMs
        started = true
    }

    fun prepare(strokes: List<PointerStroke>, gestureDownElapsedMs: Long): PrepareSegmentResult {
        if (!started) return PrepareSegmentResult.Invalid("录制尚未开始")
        if (strokes.isEmpty() || strokes.any { it.samples.isEmpty() }) {
            return PrepareSegmentResult.Invalid("手势没有有效轨迹")
        }
        if (committedSegments.size >= maxSegments) {
            return PrepareSegmentResult.LimitReached("已达到 $maxSegments 次触摸上限")
        }
        val segment = GestureSegment(
            gapBeforeMs = (gestureDownElapsedMs - readyAtMs).coerceAtLeast(0L),
            strokes = strokes,
        )
        if (durationMs + segment.gapBeforeMs + segment.durationMs > maxDurationMs) {
            return PrepareSegmentResult.LimitReached("已达到 ${maxDurationMs / 1000} 秒录制上限")
        }
        return PrepareSegmentResult.Ready(segment)
    }

    fun commit(segment: GestureSegment, nextReadyAtElapsedMs: Long) {
        check(started) { "Recording session is not active" }
        committedSegments += segment
        readyAtMs = nextReadyAtElapsedMs
    }

    fun finish(): MacroTimeline {
        started = false
        return MacroTimeline(segments = committedSegments.toList())
    }
}
