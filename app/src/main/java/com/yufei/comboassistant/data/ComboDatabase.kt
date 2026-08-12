package com.yufei.comboassistant.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ComboEntity::class], version = 1, exportSchema = true)
abstract class ComboDatabase : RoomDatabase() {
    abstract fun comboDao(): ComboDao
}
