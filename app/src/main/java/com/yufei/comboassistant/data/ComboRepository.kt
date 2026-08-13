package com.yufei.comboassistant.data

import com.yufei.comboassistant.domain.Combo
import com.yufei.comboassistant.domain.MacroTimeline
import com.yufei.comboassistant.domain.ScreenOrientation
import com.yufei.comboassistant.domain.normalized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface ComboRepository {
    fun observeAll(): Flow<List<Combo>>
    suspend fun getById(id: String): Combo?
    suspend fun save(combo: Combo)
    suspend fun saveAll(combos: List<Combo>)
    suspend fun delete(id: String)
}

@Singleton
class RoomComboRepository @Inject constructor(
    private val dao: ComboDao,
    private val json: Json,
) : ComboRepository {
    override fun observeAll(): Flow<List<Combo>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row -> runCatching { toDomain(row) }.getOrNull() }
    }

    override suspend fun getById(id: String): Combo? = dao.getById(id)?.let { row ->
        runCatching { toDomain(row) }.getOrNull()
    }

    override suspend fun save(combo: Combo) {
        dao.upsert(toEntity(combo))
    }

    override suspend fun saveAll(combos: List<Combo>) = dao.upsertAll(combos.map(::toEntity))

    override suspend fun delete(id: String) = dao.deleteById(id)

    private fun toEntity(combo: Combo): ComboEntity {
        val value = combo.normalized()
        return ComboEntity(
            id = value.id,
            name = value.name,
            targetPackage = value.targetPackage,
            orientation = value.orientation.name,
            recordedWidth = value.recordedWidth,
            recordedHeight = value.recordedHeight,
            buttonX = value.buttonX,
            buttonY = value.buttonY,
            buttonSizeDp = value.buttonSizeDp,
            buttonOpacity = value.buttonOpacity,
            speed = value.speed,
            repeatCount = value.repeatCount,
            repeatIntervalMs = value.repeatIntervalMs,
            visible = value.visible,
            timelineJson = json.encodeToString(MacroTimeline.serializer(), value.timeline),
            createdAt = value.createdAt,
            updatedAt = value.updatedAt,
        )
    }

    private fun toDomain(row: ComboEntity): Combo = Combo(
        id = row.id,
        name = row.name,
        targetPackage = row.targetPackage,
        orientation = runCatching { ScreenOrientation.valueOf(row.orientation) }
            .getOrDefault(ScreenOrientation.LANDSCAPE),
        recordedWidth = row.recordedWidth,
        recordedHeight = row.recordedHeight,
        buttonX = row.buttonX,
        buttonY = row.buttonY,
        buttonSizeDp = row.buttonSizeDp,
        buttonOpacity = row.buttonOpacity,
        speed = row.speed,
        repeatCount = row.repeatCount,
        repeatIntervalMs = row.repeatIntervalMs,
        visible = row.visible,
        timeline = json.decodeFromString(MacroTimeline.serializer(), row.timelineJson),
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
    ).normalized()
}
