package com.sdm.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadEntity::class, SegmentEntity::class, SecretEntity::class, QueueEntity::class, EventEntity::class,
        ThroughputSampleEntity::class, TransferObservationEntity::class, DailyStatEntity::class, RuleEntity::class,
        BrowserTabEntity::class, BookmarkEntity::class, HistoryEntity::class, FileIndexEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SdmDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun segments(): SegmentDao
    abstract fun secrets(): SecretDao
    abstract fun queues(): QueueDao
    abstract fun events(): EventDao
    abstract fun analytics(): AnalyticsDao
    abstract fun rules(): RuleDao
    abstract fun browser(): BrowserDao
    abstract fun files(): FileIndexDao

    companion object {
        const val NAME = "sdm.db"
        const val MAIN_QUEUE_ID = 1L
    }
}
