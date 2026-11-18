package com.sdm.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sdm.domain.DownloadState
import com.sdm.domain.HoldReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: SdmDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SdmDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun insertReadAndCountByState() = runTest {
        db.queues().upsert(QueueEntity(id = SdmDatabase.MAIN_QUEUE_ID, name = "Main"))
        val id = db.downloads().insert(DownloadEntity(originalUrl = "https://h/a.zip", fileName = "a.zip", createdAt = 1, updatedAt = 1))
        assertEquals("a.zip", db.downloads().get(id)!!.fileName)
        assertEquals(1, db.downloads().observeCounts().first().single { it.state == DownloadState.QUEUED }.count)
    }

    @Test fun compareAndSetRejectsStaleWriter() = runTest {
        val id = db.downloads().insert(DownloadEntity(originalUrl = "https://h/a.zip", fileName = "a.zip", createdAt = 1, updatedAt = 1))
        assertEquals(1, db.downloads().casState(id, DownloadState.QUEUED, DownloadState.PREPARING, HoldReason.NONE, 2))
        assertEquals(0, db.downloads().casState(id, DownloadState.QUEUED, DownloadState.PAUSED, HoldReason.NONE, 3))
        assertEquals(DownloadState.PREPARING, db.downloads().get(id)!!.state)
    }

    @Test fun segmentsCascadeWithDownload() = runTest {
        val id = db.downloads().insert(DownloadEntity(originalUrl = "https://h/a.zip", fileName = "a.zip", createdAt = 1, updatedAt = 1))
        db.segments().replace(id, listOf(SegmentEntity(id, 0, 0, 99, 50), SegmentEntity(id, 1, 100, 199, 100)))
        assertEquals(2, db.segments().forDownload(id).size)
        db.downloads().delete(listOf(id))
        assertEquals(0, db.segments().forDownload(id).size)
    }

    @Test fun dailyStatsAccumulate() = runTest {
        db.analytics().record("2026-11-18", com.sdm.core.common.FileCategory.VIDEO, 100, 1, 0, 10)
        db.analytics().record("2026-11-18", com.sdm.core.common.FileCategory.VIDEO, 50, 0, 1, 5)
        val day = db.analytics().observeDays("2026-11-01").first().single()
        assertEquals(150, day.bytes); assertEquals(1, day.completed); assertEquals(1, day.failed)
    }
}
