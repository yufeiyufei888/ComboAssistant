package com.yufei.comboassistant.domain

import kotlinx.serialization.Serializable

const val TIMELINE_SCHEMA_VERSION = 1
const val DEFAULT_BUTTON_SIZE_DP = 56f
const val DEFAULT_BUTTON_OPACITY = 0.75f
const val DEFAULT_SPEED = 1f
const val DEFAULT_REPEAT_COUNT = 1
const val DEFAULT_REPEAT_INTERVAL_MS = 100L
const val MAX_RECORDING_DURATION_MS = 60_000L
const val MAX_GESTURE_SEGMENTS = 200

@Serializable
enum class ScreenOrientation { PORTRAIT, LANDSCAPE }

@Serializable
data class TouchSample(
    val timeOffsetMs: Long,
    val x: Float,
    val y: Float,
)

@Serializable
data class PointerStroke(
    val pointerId: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val samples: List<TouchSample>,
)

@Serializable
data class GestureSegment(
    val gapBeforeMs: Long,
    val strokes: List<PointerStroke>,
) {
    val durationMs: Long
        get() = strokes.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L
}

@Serializable
data class MacroTimeline(
    val schemaVersion: Int = TIMELINE_SCHEMA_VERSION,
    val segments: List<GestureSegment> = emptyList(),
) {
    val durationMs: Long
        get() = segments.sumOf { it.gapBeforeMs + it.durationMs }
}

data class Combo(
    val id: String,
    val name: String,
    val targetPackage: String,
    val orientation: ScreenOrientation,
    val recordedWidth: Int,
    val recordedHeight: Int,
    val buttonX: Float = 0.82f,
    val buttonY: Float = 0.58f,
    val buttonSizeDp: Float = DEFAULT_BUTTON_SIZE_DP,
    val buttonOpacity: Float = DEFAULT_BUTTON_OPACITY,
    val speed: Float = DEFAULT_SPEED,
    val repeatCount: Int = DEFAULT_REPEAT_COUNT,
    val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
    val visible: Boolean = true,
    val timeline: MacroTimeline,
    val createdAt: Long,
    val updatedAt: Long,
)

data class DisplaySnapshot(
    val width: Int,
    val height: Int,
    val orientation: ScreenOrientation,
)

fun currentOrientation(width: Int, height: Int): ScreenOrientation =
    if (width >= height) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT

fun Combo.normalized(): Combo = copy(
    name = name.trim().take(40).ifBlank { "未命名连招" },
    buttonX = buttonX.coerceIn(0f, 1f),
    buttonY = buttonY.coerceIn(0f, 1f),
    buttonSizeDp = buttonSizeDp.coerceIn(36f, 96f),
    buttonOpacity = buttonOpacity.coerceIn(0.2f, 1f),
    speed = ((speed.coerceIn(0.25f, 4f) * 4).toInt() / 4f),
    repeatCount = repeatCount.coerceIn(1, 999),
    repeatIntervalMs = ((repeatIntervalMs.coerceIn(0L, 10_000L) / 50L) * 50L),
)
