package com.yufei.comboassistant.domain

enum class RecordingLimit {
    DURATION,
    SEGMENT_COUNT,
}

sealed interface AppendSegmentResult {
    data class Appended(
        val segment: GestureSegment,
        val reachedLimit: RecordingLimit? = null,
    ) : AppendSegmentResult

    data class LimitReached(
        val limit: RecordingLimit,
        val message: String,
    ) : AppendSegmentResult

    data class Invalid(val message: String) : AppendSegmentResult
}

/**
 * Builds a timeline from already-completed gestures.
 *
 * The recording clock is Android's uptime clock, matching [android.view.MotionEvent]. The time
 * before the first gesture and the time after the last gesture are deliberately not persisted;
 * only waits between two completed gestures become [GestureSegment.gapBeforeMs].
 */
class RecordingSession(
    val maxDurationMs: Long = MAX_RECORDING_DURATION_MS,
    val maxSegments: Int = MAX_GESTURE_SEGMENTS,
) {
    init {
        require(maxDurationMs > 0L) { "maxDurationMs must be positive" }
        require(maxSegments > 0) { "maxSegments must be positive" }
    }

    private val recordedSegments = mutableListOf<GestureSegment>()
    private var recordingStartedAtUptimeMs: Long? = null
    private var lastGestureUpUptimeMs: Long? = null

    val isActive: Boolean get() = recordingStartedAtUptimeMs != null
    val segmentCount: Int get() = recordedSegments.size

    /** Playback duration, excluding idle time before the first and after the last gesture. */
    val durationMs: Long get() = recordedSegments.sumOf { it.gapBeforeMs + it.durationMs }

    fun start(startedAtUptimeMs: Long) {
        require(startedAtUptimeMs >= 0L) { "startedAtUptimeMs must not be negative" }
        recordedSegments.clear()
        lastGestureUpUptimeMs = null
        recordingStartedAtUptimeMs = startedAtUptimeMs
    }

    /**
     * Appends one complete gesture. No partially captured gesture is ever added to the timeline.
     */
    fun append(
        strokes: List<PointerStroke>,
        gestureDownUptimeMs: Long,
        gestureUpUptimeMs: Long,
    ): AppendSegmentResult {
        val startedAt = recordingStartedAtUptimeMs
            ?: return AppendSegmentResult.Invalid("录制尚未开始")
        if (strokes.isEmpty() || strokes.any { it.samples.isEmpty() }) {
            return AppendSegmentResult.Invalid("手势没有有效轨迹")
        }
        if (strokes.any { stroke ->
                stroke.startOffsetMs < 0L ||
                    stroke.durationMs <= 0L ||
                    stroke.samples.any { sample ->
                        sample.timeOffsetMs < 0L ||
                            !sample.x.isFinite() || !sample.y.isFinite() ||
                            sample.x !in 0f..1f || sample.y !in 0f..1f
                    }
            }
        ) {
            return AppendSegmentResult.Invalid("手势轨迹数据无效")
        }
        if (gestureDownUptimeMs < startedAt || gestureUpUptimeMs < gestureDownUptimeMs) {
            return AppendSegmentResult.Invalid("手势时间顺序无效")
        }
        val previousUp = lastGestureUpUptimeMs
        if (previousUp != null && gestureDownUptimeMs < previousUp) {
            return AppendSegmentResult.Invalid("手势时间与上一段重叠")
        }
        if (recordedSegments.size >= maxSegments) {
            return AppendSegmentResult.LimitReached(
                limit = RecordingLimit.SEGMENT_COUNT,
                message = "已达到 $maxSegments 次触摸上限",
            )
        }

        val recordingElapsedMs = gestureUpUptimeMs - startedAt
        if (recordingElapsedMs > maxDurationMs) {
            return AppendSegmentResult.LimitReached(
                limit = RecordingLimit.DURATION,
                message = "已达到 ${maxDurationMs / 1_000} 秒录制上限",
            )
        }

        val segment = GestureSegment(
            gapBeforeMs = previousUp?.let { gestureDownUptimeMs - it } ?: 0L,
            strokes = strokes,
        )
        recordedSegments += segment
        lastGestureUpUptimeMs = gestureUpUptimeMs

        val reachedLimit = when {
            recordedSegments.size >= maxSegments -> RecordingLimit.SEGMENT_COUNT
            recordingElapsedMs >= maxDurationMs -> RecordingLimit.DURATION
            else -> null
        }
        return AppendSegmentResult.Appended(segment, reachedLimit)
    }

    /** Returns the completed timeline without adding any wait after its final gesture. */
    fun finish(): MacroTimeline {
        val timeline = MacroTimeline(segments = recordedSegments.toList())
        recordingStartedAtUptimeMs = null
        lastGestureUpUptimeMs = null
        return timeline
    }

    /** Abandons the current recording and all of its completed gestures. */
    fun cancel() {
        recordedSegments.clear()
        recordingStartedAtUptimeMs = null
        lastGestureUpUptimeMs = null
    }

    fun elapsedDurationMs(atUptimeMs: Long): Long {
        val startedAt = recordingStartedAtUptimeMs ?: return 0L
        return (atUptimeMs - startedAt).coerceAtLeast(0L)
    }

    fun remainingDurationMs(atUptimeMs: Long): Long =
        (maxDurationMs - elapsedDurationMs(atUptimeMs)).coerceAtLeast(0L)
}
