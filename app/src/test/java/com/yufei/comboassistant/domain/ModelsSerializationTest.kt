package com.yufei.comboassistant.domain

import com.yufei.comboassistant.testCombo
import com.yufei.comboassistant.testSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun timelineRoundTripsWithSchemaAndMultiPointerData() {
        val firstStroke = testSegment().strokes.first()
        val timeline = MacroTimeline(
            segments = listOf(
                GestureSegment(
                    gapBeforeMs = 75L,
                    strokes = listOf(
                        firstStroke,
                        firstStroke.copy(pointerId = 7, startOffsetMs = 15L),
                    ),
                ),
            ),
        )

        val decoded = json.decodeFromString<MacroTimeline>(json.encodeToString(timeline))

        assertEquals(TIMELINE_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(timeline, decoded)
    }

    @Test
    fun comboSettingsAreClampedToSupportedRanges() {
        val normalized = testCombo(speed = 7f, repeatCount = 2_000, repeatIntervalMs = 10_049L).copy(
            name = "   ",
            buttonX = -1f,
            buttonY = 2f,
            buttonSizeDp = 120f,
            buttonOpacity = 0.1f,
        ).normalized()

        assertEquals("未命名连招", normalized.name)
        assertEquals(0f, normalized.buttonX)
        assertEquals(1f, normalized.buttonY)
        assertEquals(96f, normalized.buttonSizeDp)
        assertEquals(0.2f, normalized.buttonOpacity)
        assertEquals(4f, normalized.speed)
        assertEquals(999, normalized.repeatCount)
        assertEquals(10_000L, normalized.repeatIntervalMs)
    }
}
