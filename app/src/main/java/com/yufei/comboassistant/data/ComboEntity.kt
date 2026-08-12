package com.yufei.comboassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "combos")
data class ComboEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetPackage: String,
    val orientation: String,
    val recordedWidth: Int,
    val recordedHeight: Int,
    val buttonX: Float,
    val buttonY: Float,
    val buttonSizeDp: Float,
    val buttonOpacity: Float,
    val speed: Float,
    val repeatCount: Int,
    val repeatIntervalMs: Long,
    val visible: Boolean,
    val timelineJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
