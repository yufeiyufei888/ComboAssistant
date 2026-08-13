package com.yufei.comboassistant.data

import com.yufei.comboassistant.domain.ScreenOrientation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboRepositoryTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `v0_1 timeline JSON is read without migration or data loss`() = runTest {
        val legacy = entity(
            id = "legacy-v0.1",
            timelineJson = V0_1_TIMELINE_JSON,
        )
        val repository = RoomComboRepository(FakeComboDao(listOf(legacy)), json)

        val combo = repository.getById(legacy.id)

        requireNotNull(combo)
        assertEquals("旧版连招", combo.name)
        assertEquals(ScreenOrientation.LANDSCAPE, combo.orientation)
        assertEquals(1, combo.timeline.schemaVersion)
        assertEquals(1, combo.timeline.segments.size)
        assertEquals(125L, combo.timeline.segments.single().gapBeforeMs)
        assertEquals(80L, combo.timeline.segments.single().durationMs)
        assertEquals(2, combo.timeline.segments.single().strokes.single().samples.size)
    }

    @Test
    fun `malformed timeline row is isolated from valid legacy rows`() = runTest {
        val valid = entity(id = "valid", timelineJson = V0_1_TIMELINE_JSON)
        val malformed = entity(id = "malformed", timelineJson = "{not-json")
        val repository = RoomComboRepository(FakeComboDao(listOf(malformed, valid)), json)

        val observed = repository.observeAll().first()

        assertEquals(listOf("valid"), observed.map { it.id })
        assertNull(repository.getById("malformed"))
        assertTrue(repository.getById("valid")?.timeline?.segments?.isNotEmpty() == true)
    }

    private fun entity(id: String, timelineJson: String) = ComboEntity(
        id = id,
        name = "旧版连招",
        targetPackage = "com.example.game",
        orientation = "LANDSCAPE",
        recordedWidth = 2400,
        recordedHeight = 1080,
        buttonX = 0.82f,
        buttonY = 0.58f,
        buttonSizeDp = 56f,
        buttonOpacity = 0.75f,
        speed = 1f,
        repeatCount = 1,
        repeatIntervalMs = 100L,
        visible = true,
        timelineJson = timelineJson,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakeComboDao(initialRows: List<ComboEntity>) : ComboDao {
        private val rows = MutableStateFlow(initialRows)

        override fun observeAll(): Flow<List<ComboEntity>> = rows

        override suspend fun getById(id: String): ComboEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(combo: ComboEntity) {
            rows.value = rows.value.filterNot { it.id == combo.id } + combo
        }

        override suspend fun upsertAll(combos: List<ComboEntity>) {
            val ids = combos.mapTo(mutableSetOf()) { it.id }
            rows.value = rows.value.filterNot { it.id in ids } + combos
        }

        override suspend fun delete(combo: ComboEntity) {
            deleteById(combo.id)
        }

        override suspend fun deleteById(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private companion object {
        const val V0_1_TIMELINE_JSON =
            """{"schemaVersion":1,"segments":[{"gapBeforeMs":125,"strokes":[{"pointerId":0,"startOffsetMs":0,"durationMs":80,"samples":[{"timeOffsetMs":0,"x":0.1,"y":0.2},{"timeOffsetMs":80,"x":0.8,"y":0.7}]}]}]}"""
    }
}
