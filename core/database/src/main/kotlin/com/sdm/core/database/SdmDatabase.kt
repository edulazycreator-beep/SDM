package com.sdm.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadEntity::class, SegmentEntity::class, SecretEntity::class, QueueEntity::class, EventEntity::class,
        ThroughputSampleEntity::class, TransferObservationEntity::class, DailyStatEntity::class, RuleEntity::class,
        BrowserTabEntity::class, BookmarkEntity::class, HistoryEntity::class, FileIndexEntity::class,
    ],
    version = 2,
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

        /** v1 -> v2: stream downloads (HLS). Nullable columns, so existing rows stay valid. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `streamType` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `streamAudioUrl` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `streamLabel` TEXT")
            }
        }
    }
}
