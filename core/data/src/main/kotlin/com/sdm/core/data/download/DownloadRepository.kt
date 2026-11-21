package com.sdm.core.data.download

import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.common.Redactor
import com.sdm.core.common.Urls
import com.sdm.core.data.analytics.AnalyticsRecorder
import com.sdm.core.data.rules.RulesRepository
import com.sdm.core.data.security.SecretStore
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.DownloadEntity
import com.sdm.core.database.EventEntity
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.DestinationType
import com.sdm.domain.Download
import com.sdm.domain.DownloadEventRecord
import com.sdm.domain.DownloadState
import com.sdm.domain.DuplicateCandidate
import com.sdm.domain.DuplicateDetector
import com.sdm.domain.DuplicateMatch
import com.sdm.domain.EventType
import com.sdm.domain.LiveProgress
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.Priority
import com.sdm.domain.RangeSupport
import com.sdm.domain.intelligence.Recommendation
import com.sdm.domain.intelligence.SpeedIntelligence
import com.sdm.domain.rules.DownloadFacts
import com.sdm.domain.rules.EnqueueDecision
import com.sdm.domain.rules.RuleEngine
import com.sdm.engine.manager.DownloadEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the confirmation dialog shows after inspecting a link (spec section 8). */
data class LinkInfo(
    val url: String,
    val finalUrl: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val category: FileCategory,
    val rangeSupported: Boolean,
    val error: String? = null,
)

data class NewDownload(
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val sourcePageUrl: String? = null,
    val category: FileCategory = FileCategory.of(fileName, mimeType),
    val destinationType: DestinationType? = null,
    val treeUri: String? = null,
    val treeLabel: String? = null,
    val connections: Int = 4,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val speedLimitBps: Long = 0,
    val priority: Priority = Priority.NORMAL,
    val scheduledAt: Long? = null,
    val checksumAlgorithm: String? = null,
    val checksumExpected: String? = null,
    val startPaused: Boolean = false,
    val queueId: Long = SdmDatabase.MAIN_QUEUE_ID,
    /** Set for HLS streams: [url] is the chosen video playlist. */
    val streamType: String? = null,
    val streamAudioUrl: String? = null,
    val streamLabel: String? = null,
    val streamSubtitles: List<com.sdm.engine.hls.SubtitleTrack> = emptyList(),
    val streamAudioOnly: Boolean = false,
    /** Alternative links for the same file. */
    val mirrors: List<String> = emptyList(),
)

@Singleton
class DownloadRepository @Inject constructor(
    private val db: SdmDatabase,
    private val coordinator: DownloadCoordinator,
    private val engine: DownloadEngine,
    private val secrets: SecretStore,
    private val rules: RulesRepository,
    private val settings: SettingsRepository,
    private val analytics: AnalyticsRecorder,
    private val outputs: OutputStore,
    private val streams: com.sdm.engine.hls.StreamInspector,
    private val remote: com.sdm.engine.remote.RemoteFileEngine,
) {
    private val dao = db.downloads()

    val downloads: Flow<List<Download>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    val live: StateFlow<Map<Long, LiveProgress>> = coordinator.live
    val counts: Flow<Map<DownloadState, Int>> = dao.observeCounts().map { l -> l.associate { it.state to it.count } }
    val completedBytes: Flow<Long> = dao.observeCompletedBytes()
    fun recent(limit: Int = 6): Flow<List<Download>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    fun observe(id: Long): Flow<Download?> = dao.observe(id).map { it?.toDomain() }
    fun events(id: Long): Flow<List<DownloadEventRecord>> = db.events().observeFor(id).map { l -> l.map { it.toDomain() } }
    fun recentEvents(limit: Int = 30): Flow<List<DownloadEventRecord>> = db.events().observeRecent(limit).map { l -> l.map { it.toDomain() } }
    fun problemEvents(): Flow<List<DownloadEventRecord>> = db.events().observeProblems().map { l -> l.map { it.toDomain() } }
    suspend fun get(id: Long): Download? = dao.get(id)?.toDomain()
    suspend fun segments(id: Long) = db.segments().forDownload(id)

    /** Reads only response headers; never downloads the body. */
    suspend fun inspect(url: String, headers: Map<String, String> = emptyMap(), contentDispositionHint: String? = null, mimeHint: String? = null): LinkInfo {
        val clean = url.trim()
        if (com.sdm.engine.remote.RemoteFileEngine.handles(clean)) return inspectRemote(clean, headers)
        if (!Urls.isValidHttpUrl(clean)) return LinkInfo(clean, clean, FileNames.resolve(clean, contentDispositionHint, mimeHint), mimeHint, null, FileCategory.OTHER, false, "Not a valid http(s), FTP or SFTP link")
        val r = engine.inspect(clean, headers)
        return r.fold(
            onSuccess = { info ->
                val mime = info.contentType?.substringBefore(';')?.trim() ?: mimeHint
                val name = FileNames.resolve(info.finalUrl, info.contentDisposition ?: contentDispositionHint, mime).let {
                    if (FileNames.fromUrl(info.finalUrl) == null) FileNames.resolve(clean, info.contentDisposition ?: contentDispositionHint, mime) else it
                }
                LinkInfo(clean, info.finalUrl, name, mime, info.totalBytes, FileCategory.of(name, mime), info.rangeSupported,
                    if (mime?.startsWith("text/html") == true) "This link opens a web page, not a file. Open it in the browser to find the download." else null)
            },
            onFailure = { e ->
                val name = FileNames.resolve(clean, contentDispositionHint, mimeHint)
                LinkInfo(clean, clean, name, mimeHint, null, FileCategory.of(name, mimeHint), false, "Could not read file details: ${Redactor.text(e.message ?: e.javaClass.simpleName)}")
            },
        )
    }

    private suspend fun inspectRemote(url: String, headers: Map<String, String>): LinkInfo {
        if (!Urls.isValidDownloadUrl(url)) return LinkInfo(url, url, "download", null, null, FileCategory.OTHER, false, "Not a valid FTP or SFTP link")
        val r = remote.probe(url, headers)
        val name = FileNames.sanitize(r.getOrNull()?.name ?: FileNames.fromUrl(url) ?: "download")
        val mime = com.sdm.core.common.MimeTypes.forName(name)
        return r.fold(
            { info -> LinkInfo(url, url, name, mime, info.size, FileCategory.of(name, mime), true, null) },
            { e -> LinkInfo(url, url, name, mime, null, FileCategory.of(name, mime), true, "Could not read file details: ${Redactor.text(e.message ?: e.javaClass.simpleName)}") },
        )
    }

    /** Lists qualities of an HLS stream (playlists only) and whether it can be downloaded. */
    suspend fun inspectStream(url: String, headers: Map<String, String> = emptyMap()): Result<com.sdm.engine.hls.StreamInfo> =
        runCatching { streams.inspect(url.trim(), headers) }

    suspend fun applyRules(info: LinkInfo): EnqueueDecision = RuleEngine.onEnqueue(rules.current(), DownloadFacts(info.fileName, info.mimeType, info.sizeBytes, info.finalUrl))

    suspend fun findDuplicate(url: String, fileName: String, size: Long?): DuplicateMatch? {
        val existing = dao.all().map { DuplicateCandidate(it.id, it.originalUrl, it.fileName, it.totalBytes, it.state, it.checksumActual?.takeIf { _ -> it.checksumAlgorithm?.contains("256") == true }) }
        return DuplicateDetector.find(url, fileName, size, null, existing)
    }

    suspend fun recommendation(url: String, userMax: Int): Recommendation =
        SpeedIntelligence.recommend(Urls.host(url), analytics.observations(), userMax, settings.current().speedProfile)

    suspend fun enqueue(input: NewDownload): Long {
        require(!com.sdm.core.common.SourcePolicy.isBlocked(input.url) && !com.sdm.core.common.SourcePolicy.isBlocked(input.sourcePageUrl)) { com.sdm.core.common.SourcePolicy.MESSAGE }
        // Never store FTP/SFTP passwords in the link itself: move them into the encrypted headers.
        val n = if (com.sdm.engine.remote.RemoteFileEngine.handles(input.url)) {
            val (clean, login) = com.sdm.engine.remote.RemoteLogin.split(input.url)
            if (login == null) input else input.copy(url = clean, headers = input.headers + login.header())
        } else input
        val now = System.currentTimeMillis()
        val treeUri = n.treeUri
        val type = n.destinationType ?: if (treeUri != null) DestinationType.SAF_TREE else outputs.defaultType()
        val entity = DownloadEntity(
            originalUrl = n.url.trim(), fileName = FileNames.sanitize(n.fileName), mimeType = n.mimeType, category = n.category,
            totalBytes = n.sizeBytes, state = if (n.startPaused) DownloadState.PAUSED else DownloadState.QUEUED, priority = n.priority,
            queueId = n.queueId, queuePosition = dao.nextPosition(n.queueId), maxConnections = n.connections.coerceIn(1, 16),
            speedLimitBps = n.speedLimitBps, networkPolicy = n.networkPolicy, destinationType = type, destinationTreeUri = treeUri,
            destinationLabel = n.treeLabel ?: if (type == DestinationType.MEDIASTORE) "Download/SDM/${n.category.folder}" else "App storage/SDM/${n.category.folder}",
            scheduledAt = n.scheduledAt, checksumAlgorithm = n.checksumAlgorithm?.takeIf { !n.checksumExpected.isNullOrBlank() },
            checksumExpected = n.checksumExpected?.trim()?.ifBlank { null }, sourcePageUrl = n.sourcePageUrl,
            hasSecrets = n.headers.isNotEmpty(), createdAt = now, updatedAt = now,
            streamType = n.streamType, streamAudioUrl = n.streamAudioUrl, streamLabel = n.streamLabel,
            streamSubtitles = n.streamSubtitles.takeIf { it.isNotEmpty() }?.joinToString("\n") { t ->
                listOf(t.language.orEmpty(), t.name, t.url).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            },
            streamAudioOnly = n.streamAudioOnly,
            mirrors = n.mirrors.map { it.trim() }.filter { com.sdm.core.common.Urls.isValidHttpUrl(it) && it != n.url }.distinct().joinToString("\n").ifBlank { null },
        )
        val id = dao.insert(entity)
        secrets.put(id, n.headers)
        db.events().insert(EventEntity(downloadId = id, timestamp = now, type = EventType.CREATED, detail = Redactor.url(n.url).take(300)))
        coordinator.start()
        coordinator.signal()
        return id
    }

    suspend fun pause(id: Long) = coordinator.pause(id)
    suspend fun resume(id: Long) = coordinator.resume(id)
    suspend fun cancel(id: Long) = coordinator.cancel(id, deletePartial = true)
    suspend fun restart(id: Long) = coordinator.restart(id)
    suspend fun delete(ids: Collection<Long>, deleteFiles: Boolean) = coordinator.delete(ids, deleteFiles)
    suspend fun setSpeedLimit(id: Long, bps: Long) = coordinator.setSpeedLimit(id, bps)

    suspend fun pauseAll() = dao.all().filter { it.state.isActive || it.state == DownloadState.QUEUED || it.state == DownloadState.WAITING_FOR_NETWORK }.forEach { coordinator.pause(it.id) }
    suspend fun resumeAll() = dao.all().filter { it.state == DownloadState.PAUSED }.forEach { coordinator.resume(it.id) }
    suspend fun removeCompleted() = dao.all().filter { it.state == DownloadState.COMPLETED }.map { it.id }.let { if (it.isNotEmpty()) dao.delete(it) }

    suspend fun update(id: Long, transform: (DownloadEntity) -> DownloadEntity) {
        val e = dao.get(id) ?: return
        dao.updatePreservingState(transform(e).copy(updatedAt = System.currentTimeMillis()))
        coordinator.signal()
    }

    suspend fun setPriority(id: Long, p: Priority) = update(id) { it.copy(priority = p) }
    suspend fun setImportant(id: Long, important: Boolean) = update(id) { it.copy(important = important) }
    suspend fun setConnections(id: Long, c: Int) = update(id) { it.copy(maxConnections = c.coerceIn(1, 16)) }
    suspend fun setNetworkPolicy(id: Long, p: NetworkPolicy) = update(id) { it.copy(networkPolicy = p) }
    suspend fun setSchedule(id: Long, at: Long?) = update(id) { it.copy(scheduledAt = at) }
    suspend fun setChecksum(id: Long, algorithm: String?, expected: String?) = update(id) { it.copy(checksumAlgorithm = algorithm, checksumExpected = expected?.trim()?.ifBlank { null }) }

    /** Reorders the queue to the given id order (drag-and-drop result). */
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> dao.setPosition(id, index.toLong()) }
        coordinator.signal()
    }

    /** "Refresh link": keeps downloaded bytes; the engine re-validates size and validators before reusing them. */
    suspend fun editUrl(id: Long, newUrl: String): Boolean {
        if (!Urls.isValidHttpUrl(newUrl)) return false
        val e = dao.get(id) ?: return false
        if (e.state.isActive) return false
        dao.updatePreservingState(e.copy(originalUrl = newUrl.trim(), resolvedUrl = null, errorCode = null, errorMessage = null, httpStatus = null, updatedAt = System.currentTimeMillis()))
        db.events().insert(EventEntity(downloadId = id, timestamp = System.currentTimeMillis(), type = EventType.RESUMED, detail = "Link updated to ${Redactor.url(newUrl)}"))
        return true
    }

    suspend fun rename(id: Long, newName: String): Boolean {
        val e = dao.get(id) ?: return false
        val clean = FileNames.sanitize(newName)
        val uri = e.destinationUri
        if (uri == null) { dao.updatePreservingState(e.copy(fileName = clean, category = FileCategory.of(clean, e.mimeType), updatedAt = System.currentTimeMillis())); return true }
        if (e.state.isActive) return false
        val renamed = outputs.rename(uri, clean) ?: return false
        dao.updatePreservingState(e.copy(fileName = clean, destinationUri = renamed, updatedAt = System.currentTimeMillis()))
        db.files().delete(listOf(uri))
        return true
    }

    /** Destination can only change before any byte is written. */
    suspend fun changeDestination(id: Long, treeUri: String?, label: String?, category: FileCategory?): Boolean {
        val e = dao.get(id) ?: return false
        if (e.destinationUri != null || e.downloadedBytes > 0) return false
        dao.updatePreservingState(e.copy(
            destinationType = if (treeUri != null) DestinationType.SAF_TREE else outputs.defaultType(), destinationTreeUri = treeUri,
            category = category ?: e.category, destinationLabel = label ?: "Download/SDM/${(category ?: e.category).folder}", updatedAt = System.currentTimeMillis(),
        ))
        return true
    }

    /**
     * Saves content the browser already holds (blob: and data: URLs, which cannot be re-requested)
     * as a completed download.
     */
    suspend fun saveBytes(name: String, mime: String?, bytes: ByteArray, sourcePage: String?): Long {
        val clean = FileNames.sanitize(name)
        val category = FileCategory.of(clean, mime)
        val type = outputs.defaultType()
        val handle = outputs.create(clean, mime, category, type, null)
        outputs.open(handle.uri).use { it.write(0, bytes, 0, bytes.size); it.sync() }
        val finalUri = outputs.finalize(handle.uri, type, null, handle.displayName, mime)
        val now = System.currentTimeMillis()
        val id = dao.insert(DownloadEntity(
            originalUrl = sourcePage ?: "about:blank", fileName = handle.displayName, mimeType = mime, category = category, totalBytes = bytes.size.toLong(),
            downloadedBytes = bytes.size.toLong(), state = DownloadState.COMPLETED, destinationType = type, destinationUri = finalUri,
            destinationLabel = handle.label, rangeSupport = RangeSupport.UNSUPPORTED, sourcePageUrl = sourcePage, createdAt = now, startedAt = now,
            completedAt = now, updatedAt = now,
        ))
        db.files().upsert(listOf(com.sdm.core.database.FileIndexEntity(finalUri, handle.displayName, bytes.size.toLong(), mime, category, now, handle.label, id)))
        db.events().insert(EventEntity(downloadId = id, timestamp = now, type = EventType.COMPLETED, detail = "Saved from browser page content"))
        return id
    }

    suspend fun search(query: String): List<Download> = dao.search("%${query.trim()}%").map { it.toDomain() }

    fun rangeLabel(r: RangeSupport) = when (r) { RangeSupport.SUPPORTED -> "Supported"; RangeSupport.UNSUPPORTED -> "Not supported"; RangeSupport.UNKNOWN -> "Not checked yet" }
}
