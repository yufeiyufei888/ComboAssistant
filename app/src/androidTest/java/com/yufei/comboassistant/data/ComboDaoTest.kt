package com.yufei.comboassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComboDaoTest {
    private lateinit var database: ComboDatabase
    private lateinit var dao: ComboDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ComboDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.comboDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun upsertObserveAndDeleteRoundTrip() = runTest {
        val entity = ComboEntity(
            id = "combo-db",
            name = "数据库测试",
            targetPackage = "com.example.game",
            orientation = "LANDSCAPE",
            recordedWidth = 2400,
            recordedHeight = 1080,
            buttonX = 0.5f,
            buttonY = 0.6f,
            buttonSizeDp = 56f,
            buttonOpacity = 0.75f,
            speed = 1f,
            repeatCount = 999,
            repeatIntervalMs = 100L,
            visible = true,
            timelineJson = "{\"schemaVersion\":1,\"segments\":[]}",
            createdAt = 1L,
            updatedAt = 2L,
        )

        dao.upsert(entity)
        assertEquals(entity, dao.getById(entity.id))
        assertEquals(listOf(entity), dao.observeAll().first())
        dao.deleteById(entity.id)
        assertNull(dao.getById(entity.id))
    }
}
