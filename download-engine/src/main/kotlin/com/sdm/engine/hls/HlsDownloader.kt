package com.sdm.engine.hls

import com.sdm.engine.ErrorKind
import com.sdm.engine.TransferError
import com.sdm.engine.TransferResult
import com.sdm.engine.TransferStats
import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.connection.RetryAfter
import com.sdm.engine.connection.await
import com.sdm.engine.connection.cancellingCall
import com.sdm.engine.connection.engineDefaults
import com.sdm.engine.connection.forSegments
import com.sdm.engine.manager.HttpStatusException
import com.sdm.engine.manager.TransferFailure
import com.sdm.engine.storage.RandomAccessSink
import com.sdm.engine.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** One HLS rendition to save as a single file (segments concatenated in order). */
data class HlsRequest(
    val playlistUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val concurrency: Int = 4,
    /** Segments already written (the init segment counts as one when present). */
    val resumeSegments: Int = 0,
    val resumeBytes: Long = 0,
    val segmentRetries: Int = 4,
    val retryBaseDelayMillis: Long = 1_000,
    val checkpointIntervalMillis: Long = 2_000,
    val progressIntervalMillis: Long = 500,
    val maxSegmentBytes: Long = 256L shl 20,
)

interface HlsListener {
    fun onPlaylist(segments: Int, durationSec: Double, encrypted: Boolean, fragmentedMp4: Boolean) {}
    fun onProgress(segmentsDone: Int, segmentsTotal: Int, bytesWritten: Long, speedBps: Long) {}
    suspend fun onCheckpoint(segmentsDone: Int, bytesWritten: Long) {}
    fun onSegmentRetry(index: Int, reason: String) {}
}

/**
 * Downloads a video-on-demand HLS rendition: fetches segments in parallel, writes them strictly in
 * order, decrypts standard AES-128 segments, and refuses live streams and DRM (SAMPLE-AES, Widevine,
 * FairPlay). Resumable from (segments written, bytes written).
 */
class HlsDownloader(private val clientProvider: () -> OkHttpClient, val globalLimiter: RateLimiter = RateLimiter()) {
    private fun client() = clientProvider().forSegments()

    /** [credentialsFor] is the URL the caller's cookies/authorization belong to (see [scopedHeaders]). */
    suspend fun fetchText(url: String, headers: Map<String, String>, credentialsFor: String = url): Pair<String, String> {
        val req = Request.Builder().url(url).engineDefaults(scopedHeaders(headers, credentialsFor, url)).build()
        return client().newCall(req).await().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
            val body = r.body
            if (body.contentLength() > 8L shl 20) throw IOException("Playlist too large")
            body.string() to r.request.url.toString()
        }
    }

    suspend fun run(request: HlsRequest, sink: RandomAccessSink, limiter: RateLimiter, listener: HlsListener): TransferResult {
        val started = System.nanoTime()
        val session = AtomicLong()
        val failures = AtomicInteger()
        var segmentsTotal = 0
        fun stats() = TransferStats(segmentsTotal, failures.get(), false, false, request.concurrency, session.get(), (System.nanoTime() - started) / 1_000_000, false)
        return try {
            val (text, finalUrl) = fetchText(request.playlistUrl, request.headers)
            if (!HlsParser.isPlaylist(text)) throw TransferFailure(TransferError(ErrorKind.INVALID_RESPONSE, "Not an HLS playlist"))
            val media = if (HlsParser.isMaster(text)) {
                val best = HlsParser.parseMaster(text, finalUrl).variants.maxByOrNull { it.bandwidth ?: 0 } ?: throw TransferFailure(TransferError(ErrorKind.INVALID_RESPONSE, "Playlist has no variants"))
                val (t2, u2) = fetchText(best.uri, request.headers, request.playlistUrl)
                HlsParser.parseMedia(t2, u2)
            } else HlsParser.parseMedia(text, finalUrl)
            if (!media.endList) throw TransferFailure(TransferError(ErrorKind.UNSUPPORTED, "This is a live stream; only finished (on-demand) videos can be downloaded"))
            if (media.drm) throw TransferFailure(TransferError(ErrorKind.UNSUPPORTED, "This stream is DRM-protected and cannot be downloaded"))
            if (media.segments.isEmpty()) throw TransferFailure(TransferError(ErrorKind.INVALID_RESPONSE, "Playlist has no segments"))
            val parts = listOfNotNull(media.initSegment) + media.segments
            segmentsTotal = parts.size
            listener.onPlaylist(parts.size, media.totalDurationSec, media.encrypted, media.fragmentedMp4)
            download(request, parts, sink, listOf(limiter, globalLimiter), listener, session, failures)
            TransferResult.Completed(sink.size(), null, finalUrl, stats())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransferFailure) {
            TransferResult.Failed(e.error, stats())
        } catch (e: HttpStatusException) {
            TransferResult.Failed(TransferError(ErrorKind.HTTP, "HTTP ${e.code}", e.code, e.retryAfterMillis, request.resumeSegments > 0), stats())
        } catch (e: StorageException) {
            val t = (e.message + " " + e.cause?.message).lowercase()
            TransferResult.Failed(TransferError(if ("enospc" in t || "no space" in t) ErrorKind.STORAGE_FULL else ErrorKind.STORAGE, e.message ?: "Storage error"), stats())
        } catch (e: IOException) {
            TransferResult.Failed(TransferError(if (e is SocketTimeoutException) ErrorKind.TIMEOUT else ErrorKind.NETWORK, "${e.javaClass.simpleName}: ${e.message}"), stats())
        }
    }

    private suspend fun download(
        request: HlsRequest, parts: List<HlsSegment>, sink: RandomAccessSink, limiters: List<RateLimiter>,
        listener: HlsListener, session: AtomicLong, failures: AtomicInteger,
    ) = coroutineScope {
        val start = request.resumeSegments.coerceIn(0, parts.size)
        var position = if (start == 0) 0L else request.resumeBytes
        storage { sink.setLength(position) }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val ready = ConcurrentHashMap<Int, ByteArray>()
        val signal = Channel<Int>(Channel.UNLIMITED)
        val buffered = Semaphore((request.concurrency * 2).coerceAtLeast(2))
        val fetching = Semaphore(request.concurrency.coerceIn(1, 16))
        val client = client()
        val producer = launch {
            for (i in start until parts.size) {
                buffered.acquire()
                launch {
                    val bytes = fetching.withPermit { fetchWithRetry(client, request, parts[i], i, keys, limiters, listener, session, failures) }
                    ready[i] = bytes
                    signal.send(i)
                }
            }
        }
        var next = start
        var lastCheckpoint = System.nanoTime()
        var lastProgress = 0L
        var speedBytes = session.get(); var speedAt = System.nanoTime(); var speed = 0.0
        try {
            while (next < parts.size) {
                signal.receive()
                while (true) {
                    val bytes = ready.remove(next) ?: break
                    storage { sink.write(position, bytes, 0, bytes.size) }
                    position += bytes.size
                    next++
                    buffered.release()
                }
                val now = System.nanoTime()
                if (now - lastProgress >= request.progressIntervalMillis * 1_000_000) {
                    val b = session.get()
                    val inst = (b - speedBytes) * 1e9 / (now - speedAt).coerceAtLeast(1)
                    speed = if (speed == 0.0) inst else 0.3 * inst + 0.7 * speed
                    speedBytes = b; speedAt = now; lastProgress = now
                    listener.onProgress(next, parts.size, position, speed.toLong())
                }
                if (now - lastCheckpoint >= request.checkpointIntervalMillis * 1_000_000) {
                    storage { sink.sync() }
                    listener.onCheckpoint(next, position)
                    lastCheckpoint = now
                }
            }
        } finally {
            producer.cancel()
            withContext(NonCancellable) {
                runCatching { storage { sink.sync() } }
                listener.onCheckpoint(next, position)
            }
        }
        listener.onProgress(next, parts.size, position, speed.toLong())
    }

    private suspend fun fetchWithRetry(
        client: OkHttpClient, request: HlsRequest, seg: HlsSegment, index: Int, keys: ConcurrentHashMap<String, ByteArray>,
        limiters: List<RateLimiter>, listener: HlsListener, session: AtomicLong, failures: AtomicInteger,
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val raw = fetchBytes(client, request, seg.uri, seg.byteRange, limiters, session, request.maxSegmentBytes)
                val key = seg.key ?: return raw
                return decrypt(raw, key, seg.sequence, keyBytes(client, request, key, keys))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                failures.incrementAndGet()
                val retryable = e.code == 408 || e.code == 429 || e.code >= 500
                if (!retryable || attempt >= request.segmentRetries) throw e
                listener.onSegmentRetry(index, "HTTP ${e.code}")
                attempt++
                delay(e.retryAfterMillis?.coerceAtMost(30_000) ?: backoff(request, attempt))
            } catch (e: IOException) {
                failures.incrementAndGet()
                if (attempt >= request.segmentRetries) throw e
                listener.onSegmentRetry(index, e.javaClass.simpleName)
                attempt++
                delay(backoff(request, attempt))
            }
        }
    }

    private fun backoff(r: HlsRequest, attempt: Int) = (Random.nextDouble(0.5, 1.0) * r.retryBaseDelayMillis * (1L shl attempt.coerceAtMost(6))).toLong()

    private suspend fun fetchBytes(client: OkHttpClient, request: HlsRequest, url: String, range: LongRange?, limiters: List<RateLimiter>, session: AtomicLong, max: Long): ByteArray {
        val req = Request.Builder().url(url).engineDefaults(scopedHeaders(request.headers, request.playlistUrl, url)).apply { range?.let { header("Range", "bytes=${it.first}-${it.last}") } }.build()
        val call = client.newCall(req)
        val response = call.await()
        return cancellingCall(call) {
            withContext(Dispatchers.IO) {
                response.use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
                    if (range != null && r.code != 206) throw IOException("Server ignored the byte range of a segment")
                    val length = r.body.contentLength()
                    val out = ByteArrayOutputStream(if (length in 1..max) length.toInt() else 256 * 1024)
                    val src = r.body.source()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val n = src.read(buf, 0, buf.size)
                        if (n < 0) break
                        total += n
                        if (total > max) throw IOException("Segment larger than ${max shr 20} MB")
                        for (l in limiters) l.acquire(n)
                        out.write(buf, 0, n)
                        session.addAndGet(n.toLong())
                    }
                    if (length >= 0 && total != length) throw IOException("Segment truncated ($total of $length bytes)")
                    out.toByteArray()
                }
            }
        }
    }

    private suspend fun keyBytes(client: OkHttpClient, request: HlsRequest, key: HlsKey, cache: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val uri = key.uri ?: throw TransferFailure(TransferError(ErrorKind.UNSUPPORTED, "Encrypted segment without key URI"))
        cache[uri]?.let { return it }
        val bytes = fetchBytes(client, request, uri, null, emptyList(), AtomicLong(), 1024)
        if (bytes.size != 16) throw TransferFailure(TransferError(ErrorKind.UNSUPPORTED, "Unsupported HLS key (${bytes.size} bytes)"))
        cache[uri] = bytes
        return bytes
    }

    companion object {
        /** AES-128-CBC with PKCS7 padding; IV from the playlist or the segment's media sequence number (RFC 8216 5.2). */
        private val CREDENTIAL_HEADERS = setOf("cookie", "authorization", "proxy-authorization")

        /**
         * Segments and keys often live on other hosts (CDNs). Cookies and authorization captured for
         * the playlist's site are only sent to that host and its subdomains, never to third parties.
         */
        fun scopedHeaders(headers: Map<String, String>, credentialsFor: String, target: String): Map<String, String> {
            val owner = credentialsFor.toHttpUrlOrNull()?.host ?: return headers.filterKeys { it.lowercase() !in CREDENTIAL_HEADERS }
            val host = target.toHttpUrlOrNull()?.host
            val sameSite = host != null && (host == owner || host.endsWith(".$owner"))
            return if (sameSite) headers else headers.filterKeys { it.lowercase() !in CREDENTIAL_HEADERS }
        }

        fun decrypt(data: ByteArray, key: HlsKey, sequence: Long, keyBytes: ByteArray): ByteArray {
            val iv = key.iv?.takeIf { it.size == 16 } ?: ByteBuffer.allocate(16).putLong(8, sequence.coerceAtLeast(0)).array()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            return try { cipher.doFinal(data) } catch (e: Exception) { throw IOException("Segment decryption failed", e) }
        }

        internal inline fun <T> storage(block: () -> T): T = try { block() } catch (e: StorageException) { throw e } catch (e: IOException) { throw StorageException(e.message ?: "Storage error", e) }
    }
}
