package com.yufei.comboassistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ComboDao {
    @Query("SELECT * FROM combos ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ComboEntity>>

    @Query("SELECT * FROM combos WHERE id = :id")
    suspend fun getById(id: String): ComboEntity?

    @Upsert
    suspend fun upsert(combo: ComboEntity)

    @Delete
    suspend fun delete(combo: ComboEntity)

    @Query("DELETE FROM combos WHERE id = :id")
    suspend fun deleteById(id: String)
}
