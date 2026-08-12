package com.yufei.comboassistant

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.GestureSegment
import com.yufei.comboassistant.domain.MacroTimeline
import com.yufei.comboassistant.domain.PointerStroke
import com.yufei.comboassistant.domain.ScreenOrientation
import com.yufei.comboassistant.domain.TouchSample

fun testSegment(gapMs: Long = 40L, durationMs: Long = 100L): GestureSegment = GestureSegment(
    gapBeforeMs = gapMs,
    strokes = listOf(
        PointerStroke(
            pointerId = 0,
            startOffsetMs = 0L,
            durationMs = durationMs,
            samples = listOf(
                TouchSample(0L, 0.1f, 0.2f),
                TouchSample(durationMs, 0.8f, 0.7f),
            ),
        ),
    ),
)

fun testCombo(
    speed: Float = 1f,
    repeatCount: Int = 1,
    repeatIntervalMs: Long = 100L,
    orientation: ScreenOrientation = ScreenOrientation.LANDSCAPE,
    timeline: MacroTimeline = MacroTimeline(segments = listOf(testSegment())),
): Combo = Combo(
    id = "combo-1",
    name = "测试连招",
    targetPackage = "com.example.game",
    orientation = orientation,
    recordedWidth = 2400,
    recordedHeight = 1080,
    speed = speed,
    repeatCount = repeatCount,
    repeatIntervalMs = repeatIntervalMs,
    timeline = timeline,
    createdAt = 1L,
    updatedAt = 1L,
)
