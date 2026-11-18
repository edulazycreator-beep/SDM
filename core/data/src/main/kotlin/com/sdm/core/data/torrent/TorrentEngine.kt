package com.sdm.core.data.torrent

import android.content.Context
import android.net.Uri
import com.sdm.core.common.Urls
import com.sdm.core.data.network.HttpClientProvider
import com.sdm.engine.ErrorKind
import com.sdm.engine.TransferError
import com.sdm.engine.TransferResult
import com.sdm.engine.TransferStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** What the coordinator hears from a running torrent. */
interface TorrentListener {
    fun onMetadata(name: String, totalBytes: Long, files: Int) {}
    fun onProgress(doneBytes: Long, totalBytes: Long, downloadBps: Long, peers: Int, seeds: Int) {}
}

/**
 * BitTorrent downloads (magnet links and .torrent files) through libtorrent (libtorrent4j). Files
 * are written to a private working folder; the coordinator copies them to the chosen destination
 * when the torrent is complete. Pausing removes the torrent from the session and keeps the data;
 * resuming re-adds it and libtorrent re-checks what is already there. SDM uploads to other peers
 * only while it downloads (as the protocol requires) and stops when the download is complete.
 */
@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clients: HttpClientProvider,
) {
    private var session: SessionManager? = null
    private val users = AtomicInteger()

    @Synchronized private fun acquire(): SessionManager {
        users.incrementAndGet()
        return session ?: SessionManager(false).also { s ->
            val pack = SettingsPack().apply {
                // Light defaults for a phone; DHT on so magnet links work without trackers.
                activeDownloads(4); activeSeeds(0); connectionsLimit(200)
            }
            s.start(SessionParams(pack))
            session = s
        }
    }

    @Synchronized private fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            runCatching { session?.stop() }
            session = null
        }
    }

    fun workDir(id: Long): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrents/$id")

    /** Reads (and caches) the torrent's metadata: from a magnet link via DHT/trackers, a URL, or a file. */
    private suspend fun metadata(s: SessionManager, source: String, headers: Map<String, String>, dir: File, timeoutSec: Int): TorrentInfo {
        val cache = File(dir, ".sdm-meta.torrent")
        if (cache.length() > 0) return TorrentInfo(cache.readBytes())
        val bytes: ByteArray = when {
            Urls.isMagnet(source) -> withContext(Dispatchers.IO) { s.fetchMagnet(source, timeoutSec, dir) }
                ?: throw TorrentFailure(ErrorKind.TIMEOUT, "No peers sent the torrent's details within $timeoutSec s. Check the link or try again later.")
            source.startsWith("content:") || source.startsWith("file:") -> withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() } ?: throw IOException("Cannot read the .torrent file")
            }
            else -> withContext(Dispatchers.IO) {
                val req = Request.Builder().url(source).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                clients.get().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw TorrentFailure(ErrorKind.HTTP, "HTTP ${r.code} for the .torrent file", r.code)
                    if (r.body.contentLength() > 32L shl 20) throw TorrentFailure(ErrorKind.INVALID_RESPONSE, "This .torrent file is too large")
                    r.body.bytes()
                }
            }
        }
        val info = runCatching { TorrentInfo(bytes) }.getOrElse { throw TorrentFailure(ErrorKind.INVALID_RESPONSE, "Not a valid torrent") }
        cache.writeBytes(bytes)
        return info
    }

    suspend fun run(id: Long, source: String, headers: Map<String, String>, limitBps: Long, listener: TorrentListener): TransferResult {
        val started = System.nanoTime()
        var peak = 0
        fun stats(bytes: Long) = TransferStats(1, 0, false, false, peak, bytes, (System.nanoTime() - started) / 1_000_000, true)
        val dir = workDir(id).apply { mkdirs() }
        val s = acquire()
        var handle: TorrentHandle? = null
        var sessionBytes = 0L
        try {
            val info = metadata(s, source, headers, dir, 120)
            listener.onMetadata(info.name(), info.totalSize(), info.numFiles())
            if (limitBps > 0) s.downloadRateLimit(limitBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            s.download(info, dir)
            val hash = info.infoHash()
            var startDone = -1L
            while (true) {
                currentCoroutineContext().ensureActive()
                val h = handle ?: s.find(hash)?.takeIf { it.isValid }?.also { handle = it }
                if (h != null) {
                    val st = h.status()
                    val done = st.totalWantedDone()
                    if (startDone < 0) startDone = done
                    sessionBytes = (done - startDone).coerceAtLeast(0)
                    peak = maxOf(peak, st.numPeers())
                    listener.onProgress(done, st.totalWanted(), st.downloadPayloadRate().toLong(), st.numPeers(), st.numSeeds())
                    if (st.isFinished || (st.totalWanted() > 0 && done >= st.totalWanted())) {
                        h.pause()
                        return TransferResult.Completed(st.totalWanted(), null, source, stats(sessionBytes))
                    }
                }
                delay(1_000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TorrentFailure) {
            return TransferResult.Failed(TransferError(e.kind, e.message ?: "Torrent error", e.code), stats(sessionBytes))
        } catch (e: IOException) {
            return TransferResult.Failed(TransferError(ErrorKind.NETWORK, "${e.javaClass.simpleName}: ${e.message}"), stats(sessionBytes))
        } catch (e: Exception) {
            return TransferResult.Failed(TransferError(ErrorKind.INVALID_RESPONSE, "Torrent error: ${e.message}"), stats(sessionBytes))
        } finally {
            withContext(NonCancellable) {
                handle?.let { h -> runCatching { s.remove(h) } }
                release()
            }
        }
    }

    /** The finished files (paths relative to the torrent's top folder), without SDM's own metadata. */
    fun finishedFiles(id: Long): List<Pair<File, String>> {
        val dir = workDir(id)
        return dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".sdm-") && !it.name.endsWith(".parts") }
            .map { it to it.relativeTo(dir).path }.toList()
    }

    fun deleteWork(id: Long) { runCatching { workDir(id).deleteRecursively() } }

    private class TorrentFailure(val kind: ErrorKind, message: String, val code: Int? = null) : Exception(message)
}
