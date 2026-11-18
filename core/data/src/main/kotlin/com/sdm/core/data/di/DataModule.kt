package com.sdm.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sdm.core.data.network.HttpClientProvider
import com.sdm.core.database.SdmDatabase
import com.sdm.engine.manager.DownloadEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SdmDatabase =
        Room.databaseBuilder(context, SdmDatabase::class.java, SdmDatabase.NAME)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO queues(id, name, maxConcurrent, ordering, scheduleJson, paused) VALUES(1, 'Main', 3, 'MANUAL', NULL, 0)")
                }
            })
            .addMigrations(SdmDatabase.MIGRATION_1_2)
            .build()

    @Provides fun downloadDao(db: SdmDatabase) = db.downloads()
    @Provides fun segmentDao(db: SdmDatabase) = db.segments()
    @Provides fun secretDao(db: SdmDatabase) = db.secrets()
    @Provides fun queueDao(db: SdmDatabase) = db.queues()
    @Provides fun eventDao(db: SdmDatabase) = db.events()
    @Provides fun analyticsDao(db: SdmDatabase) = db.analytics()
    @Provides fun ruleDao(db: SdmDatabase) = db.rules()
    @Provides fun browserDao(db: SdmDatabase) = db.browser()
    @Provides fun fileIndexDao(db: SdmDatabase) = db.files()

    @Provides @Singleton
    fun engine(clients: HttpClientProvider): DownloadEngine = DownloadEngine({ clients.get() })

    /** Shares the engine's global limiter so the speed limit covers file and stream downloads together. */
    @Provides @Singleton
    fun hls(clients: HttpClientProvider, engine: DownloadEngine): com.sdm.engine.hls.HlsDownloader = com.sdm.engine.hls.HlsDownloader({ clients.get() }, engine.globalLimiter)

    @Provides @Singleton
    fun streamInspector(hls: com.sdm.engine.hls.HlsDownloader): com.sdm.engine.hls.StreamInspector = com.sdm.engine.hls.StreamInspector(hls)
}
