package com.yufei.comboassistant.di

import android.content.Context
import androidx.room.Room
import com.yufei.comboassistant.data.ComboDao
import com.yufei.comboassistant.data.ComboDatabase
import com.yufei.comboassistant.data.ComboRepository
import com.yufei.comboassistant.data.RoomComboRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ComboDatabase =
        Room.databaseBuilder(context, ComboDatabase::class.java, "combo-assistant.db")
            .build()

    @Provides
    fun provideComboDao(database: ComboDatabase): ComboDao = database.comboDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindComboRepository(repository: RoomComboRepository): ComboRepository
}
