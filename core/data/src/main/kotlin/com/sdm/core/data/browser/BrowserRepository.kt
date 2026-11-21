package com.sdm.core.data.browser

import com.sdm.core.database.BookmarkEntity
import com.sdm.core.database.BrowserDao
import com.sdm.core.database.BrowserTabEntity
import com.sdm.core.database.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Bookmarks, history and the restorable (non-private) tab session (spec section 10). */
@Singleton
class BrowserRepository @Inject constructor(private val dao: BrowserDao) {
    val bookmarks: Flow<List<BookmarkEntity>> = dao.observeBookmarks()
    val history: Flow<List<HistoryEntity>> = dao.observeHistory()
    fun isBookmarked(url: String): Flow<Boolean> = dao.observeIsBookmarked(url).map { it > 0 }

    suspend fun toggleBookmark(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        if (dao.searchBookmarks(url).any { it.url == url }) dao.removeBookmark(url)
        else dao.addBookmark(BookmarkEntity(url = url, title = title.ifBlank { url }, createdAt = System.currentTimeMillis()))
    }

    suspend fun removeBookmark(url: String) = dao.removeBookmark(url)

    /** Private tabs never call this. */
    suspend fun recordVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        val existing = dao.historyFor(url)
        dao.upsertHistory(
            existing?.copy(title = title.ifBlank { existing.title }, lastVisited = System.currentTimeMillis(), visitCount = existing.visitCount + 1)
                ?: HistoryEntity(url = url, title = title.ifBlank { url }, lastVisited = System.currentTimeMillis()),
        )
    }

    suspend fun clearHistory() = dao.clearHistory()
    suspend fun deleteHistory(id: Long) = dao.deleteHistory(id)
    suspend fun savedTabs(): List<BrowserTabEntity> = dao.tabs()
    suspend fun saveTab(id: String, url: String, title: String, position: Int) = dao.saveTab(BrowserTabEntity(id, url, title, position, System.currentTimeMillis()))
    suspend fun deleteTab(id: String) = dao.deleteTab(id)
    suspend fun searchBookmarks(q: String) = dao.searchBookmarks("%$q%")
    suspend fun searchHistory(q: String) = dao.searchHistory("%$q%")
}
