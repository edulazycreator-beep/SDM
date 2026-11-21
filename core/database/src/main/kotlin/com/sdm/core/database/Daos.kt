package com.sdm.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.sdm.domain.DownloadState
import kotlinx.coroutines.flow.Flow

data class StateCount(val state: DownloadState, val count: Int)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN (:states)")
    suspend fun inStates(states: List<DownloadState>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN (:states)")
    fun observeInStates(states: List<DownloadState>): Flow<List<DownloadEntity>>

    @Query("SELECT state, COUNT(*) AS count FROM downloads GROUP BY state")
    fun observeCounts(): Flow<List<StateCount>>

    @Query("SELECT COALESCE(SUM(downloadedBytes), 0) FROM downloads WHERE state = 'COMPLETED'")
    fun observeCompletedBytes(): Flow<Long>

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DownloadEntity>>

    @Query("SELECT COALESCE(MAX(queuePosition), 0) + 1 FROM downloads WHERE queueId = :queueId")
    suspend fun nextPosition(queueId: Long): Long

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    /**
     * Metadata update that never changes the state: state, hold reason and version are re-read in the
     * same transaction, so a concurrent compare-and-set state change cannot be overwritten.
     */
    @Transaction
    suspend fun updatePreservingState(entity: DownloadEntity) {
        val current = get(entity.id) ?: return
        update(entity.copy(state = current.state, holdReason = current.holdReason, version = current.version))
    }

    /** Compare-and-set state change: returns 0 when another writer changed the state first. */
    @Query("UPDATE downloads SET state = :to, holdReason = :hold, updatedAt = :now, version = version + 1 WHERE id = :id AND state = :from")
    suspend fun casState(id: Long, from: DownloadState, to: DownloadState, hold: com.sdm.domain.HoldReason, now: Long): Int

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, activeConnections = :connections, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long?, connections: Int, now: Long)

    @Query("UPDATE downloads SET queuePosition = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Long)

    @Query("UPDATE downloads SET holdReason = :hold WHERE id = :id AND state = 'QUEUED'")
    suspend fun setHold(id: Long, hold: com.sdm.domain.HoldReason)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM downloads WHERE fileName LIKE :q OR originalUrl LIKE :q ORDER BY createdAt DESC LIMIT 200")
    suspend fun search(q: String): List<DownloadEntity>
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM download_segments WHERE downloadId = :downloadId ORDER BY startByte")
    suspend fun forDownload(downloadId: Long): List<SegmentEntity>

    @Query("DELETE FROM download_segments WHERE downloadId = :downloadId")
    suspend fun clear(downloadId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Transaction
    suspend fun replace(downloadId: Long, segments: List<SegmentEntity>) {
        clear(downloadId)
        insertAll(segments)
    }
}

@Dao
interface SecretDao {
    @Upsert suspend fun put(secret: SecretEntity)
    @Query("SELECT * FROM download_secrets WHERE downloadId = :id") suspend fun get(id: Long): SecretEntity?
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY id") fun observeAll(): Flow<List<QueueEntity>>
    @Query("SELECT * FROM queues ORDER BY id") suspend fun all(): List<QueueEntity>
    @Query("SELECT * FROM queues WHERE id = :id") suspend fun get(id: Long): QueueEntity?
    @Upsert suspend fun upsert(queue: QueueEntity): Long
}

@Dao
interface EventDao {
    @Insert suspend fun insert(event: EventEntity)
    @Query("SELECT * FROM download_events WHERE downloadId = :id ORDER BY timestamp DESC LIMIT 200") fun observeFor(id: Long): Flow<List<EventEntity>>
    @Query("SELECT * FROM download_events ORDER BY timestamp DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<EventEntity>>
    @Query("SELECT * FROM download_events WHERE type IN ('FAILED','SEGMENT_FAILED','RETRY_SCHEDULED','LEASE_REVOKED','FALLBACK_SINGLE_CONNECTION','RESOURCE_CHANGED') ORDER BY timestamp DESC LIMIT 300")
    fun observeProblems(): Flow<List<EventEntity>>
    @Query("DELETE FROM download_events WHERE timestamp < :before") suspend fun prune(before: Long)
}

data class CategoryBytes(val category: com.sdm.core.common.FileCategory, val bytes: Long, val count: Int)
data class DayTotal(val day: String, val bytes: Long, val completed: Int, val failed: Int, val durationMillis: Long)

@Dao
interface AnalyticsDao {
    @Insert suspend fun insertSample(sample: ThroughputSampleEntity)
    @Query("SELECT * FROM throughput_samples WHERE timestamp >= :since ORDER BY timestamp") fun observeSamples(since: Long): Flow<List<ThroughputSampleEntity>>
    @Query("DELETE FROM throughput_samples WHERE timestamp < :before") suspend fun pruneSamples(before: Long)

    @Insert suspend fun insertObservation(o: TransferObservationEntity)
    @Query("SELECT * FROM transfer_observations ORDER BY timestamp DESC LIMIT 500") suspend fun observations(): List<TransferObservationEntity>
    @Query("SELECT * FROM transfer_observations ORDER BY timestamp DESC LIMIT 500") fun observeObservations(): Flow<List<TransferObservationEntity>>

    @Query("INSERT OR IGNORE INTO daily_stats(day, category, bytes, completed, failed, durationMillis) VALUES(:day, :category, 0, 0, 0, 0)")
    suspend fun ensureDay(day: String, category: com.sdm.core.common.FileCategory)

    @Query("UPDATE daily_stats SET bytes = bytes + :bytes, completed = completed + :completed, failed = failed + :failed, durationMillis = durationMillis + :duration WHERE day = :day AND category = :category")
    suspend fun addToDay(day: String, category: com.sdm.core.common.FileCategory, bytes: Long, completed: Int, failed: Int, duration: Long)

    @Transaction
    suspend fun record(day: String, category: com.sdm.core.common.FileCategory, bytes: Long, completed: Int, failed: Int, duration: Long) {
        ensureDay(day, category)
        addToDay(day, category, bytes, completed, failed, duration)
    }

    @Query("SELECT day, SUM(bytes) AS bytes, SUM(completed) AS completed, SUM(failed) AS failed, SUM(durationMillis) AS durationMillis FROM daily_stats WHERE day >= :fromDay GROUP BY day ORDER BY day")
    fun observeDays(fromDay: String): Flow<List<DayTotal>>

    @Query("SELECT category, SUM(bytes) AS bytes, SUM(completed) AS count FROM daily_stats GROUP BY category")
    fun observeCategories(): Flow<List<CategoryBytes>>

    @Query("DELETE FROM daily_stats") suspend fun clearDaily()
    @Query("DELETE FROM throughput_samples") suspend fun clearSamples()
    @Query("DELETE FROM transfer_observations") suspend fun clearObservations()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority, id") fun observeAll(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules ORDER BY priority, id") suspend fun all(): List<RuleEntity>
    @Query("SELECT COUNT(*) FROM rules") suspend fun count(): Int
    @Upsert suspend fun upsert(rule: RuleEntity): Long
    @Query("DELETE FROM rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM rules") suspend fun clear()
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_tabs ORDER BY position") suspend fun tabs(): List<BrowserTabEntity>
    @Upsert suspend fun saveTab(tab: BrowserTabEntity)
    @Query("DELETE FROM browser_tabs WHERE id = :id") suspend fun deleteTab(id: String)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun observeBookmarks(): Flow<List<BookmarkEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE url = :url") suspend fun removeBookmark(url: String)
    @Query("SELECT COUNT(*) FROM bookmarks WHERE url = :url") fun observeIsBookmarked(url: String): Flow<Int>
    @Query("SELECT * FROM bookmarks WHERE title LIKE :q OR url LIKE :q LIMIT 100") suspend fun searchBookmarks(q: String): List<BookmarkEntity>

    @Query("SELECT * FROM history ORDER BY lastVisited DESC LIMIT 500") fun observeHistory(): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history WHERE url = :url") suspend fun historyFor(url: String): HistoryEntity?
    @Upsert suspend fun upsertHistory(h: HistoryEntity)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE id = :id") suspend fun deleteHistory(id: Long)
    @Query("SELECT * FROM history WHERE title LIKE :q OR url LIKE :q ORDER BY lastVisited DESC LIMIT 100") suspend fun searchHistory(q: String): List<HistoryEntity>
}

@Dao
interface FileIndexDao {
    @Query("SELECT * FROM file_index ORDER BY modifiedAt DESC") fun observeAll(): Flow<List<FileIndexEntity>>
    @Query("SELECT * FROM file_index") suspend fun all(): List<FileIndexEntity>
    @Upsert suspend fun upsert(items: List<FileIndexEntity>)
    @Query("DELETE FROM file_index WHERE uri IN (:uris)") suspend fun delete(uris: List<String>)
    @Query("DELETE FROM file_index WHERE sourceTreeUri IS :tree") suspend fun deleteFromSource(tree: String?)
    @Query("UPDATE file_index SET sha256 = :sha, sha256ComputedAt = :at WHERE uri = :uri") suspend fun setSha(uri: String, sha: String, at: Long)
    @Query("SELECT * FROM file_index WHERE name LIKE :q ORDER BY modifiedAt DESC LIMIT 200") suspend fun search(q: String): List<FileIndexEntity>
}
