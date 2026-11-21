package com.sdm.engine.manager

import com.sdm.engine.Checkpoint
import com.sdm.engine.EngineEventType
import com.sdm.engine.ErrorKind
import com.sdm.engine.ProbeInfo
import com.sdm.engine.SegmentState
import com.sdm.engine.TransferError
import com.sdm.engine.TransferListener
import com.sdm.engine.TransferRequest
import com.sdm.engine.TransferResult
import com.sdm.engine.TransferStats
import com.sdm.engine.Validators
import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.connection.RetryAfter
import com.sdm.engine.connection.await
import com.sdm.engine.connection.cancellingCall
import com.sdm.engine.connection.engineDefaults
import com.sdm.engine.connection.forSegments
import com.sdm.engine.segment.SegmentPlanner
import com.sdm.engine.storage.RandomAccessSink
import com.sdm.engine.storage.StorageException
import com.sdm.engine.validator.ContentRange
import com.sdm.engine.validator.RangeCheck
import com.sdm.engine.validator.RangeValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Segmented HTTP download engine. Pure JVM: storage, persistence and Android execution are
 * reached through [RandomAccessSink] and [TransferListener].
 */
class DownloadEngine(
    private val clientProvider: () -> OkHttpClient,
    val globalLimiter: RateLimiter = RateLimiter(),
) {
    @Volatile private var cachedBase: OkHttpClient? = null
    @Volatile private var cachedSegmentClient: OkHttpClient? = null

    private fun client(): OkHttpClient {
        val base = clientProvider()
        if (base !== cachedBase || cachedSegmentClient == null) {
            cachedSegmentClient = base.forSegments()
            cachedBase = base
        }
        return cachedSegmentClient!!
    }

    suspend fun run(request: TransferRequest, sink: RandomAccessSink, limiter: RateLimiter, listener: TransferListener): TransferResult =
        Transfer(client(), request, sink, listOf(limiter, globalLimiter), listener).execute()

    /** Reads headers only (file name, size, type, range support) for the confirmation dialog. */
    suspend fun inspect(url: String, headers: Map<String, String>): Result<ProbeInfo> = runCatching {
        val req = Request.Builder().url(url).engineDefaults(headers).header("Range", "bytes=0-").build()
        client().newCall(req).await().use { r ->
            if (!r.isSuccessful && r.code != 416) throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
            val cr = ContentRange.parse(r.header("Content-Range"))
            val ranged = r.code == 206 && cr?.start == 0L && cr.total != null
            val total = if (ranged) cr?.total else if (r.code == 416) cr?.total else r.body.contentLength().takeIf { it >= 0 }
            ProbeInfo(r.request.url.toString(), r.code, total, ranged, validatorsOf(r), r.header("Content-Type"), r.header("Content-Disposition"))
        }
    }

    companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val OVERLAP_CHECK_BYTES = 64 * 1024

        internal fun validatorsOf(r: Response) = Validators(r.header("ETag"), r.header("Last-Modified"))
    }
}

class HttpStatusException(val code: Int, val retryAfterMillis: Long?) : IOException("HTTP $code")
class RangeProblem(message: String) : IOException(message)
class TransferFailure(val error: TransferError) : Exception(error.message)

private class Seg(val index: Int, val start: Long, end: Long, position: Long) {
    val end = AtomicLong(end)
    val pos = AtomicLong(position)
    @Volatile var assigned = false
    val done: Boolean get() = pos.get() > end.get()
    val remaining: Long get() = end.get() - pos.get() + 1
    fun state() = SegmentState(index, start, end.get(), pos.get())
}

private class Opened(val call: Call, val response: Response)

private enum class WorkerOutcome { DONE, THROTTLED }

private class Transfer(
    private val client: OkHttpClient,
    private val request: TransferRequest,
    private val sink: RandomAccessSink,
    private val limiters: List<RateLimiter>,
    private val listener: TransferListener,
) {
    private val mutex = Mutex()
    private val segs = mutableListOf<Seg>()
    private var total: Long? = null
    private var rangeSupported = false
    private var validators = Validators()
    private var resolvedUrl = request.checkpoint?.resolvedUrl ?: request.url
    private val sessionBytes = AtomicLong()
    private val segmentFailures = AtomicInteger()
    private val throttled = AtomicBoolean()
    private var fellBack = false
    private val activeWorkers = AtomicInteger()
    private val maxWorkers = AtomicInteger()
    private val startedAt = System.nanoTime()
    private val resumed = (request.checkpoint?.downloadedBytes ?: 0) > 0
    private var probed = false

    suspend fun execute(): TransferResult = try {
        coroutineScope { runTransfer() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransferFailure) {
        TransferResult.Failed(e.error, stats())
    } catch (e: HttpStatusException) {
        TransferResult.Failed(httpError(e), stats())
    } catch (e: RangeProblem) {
        TransferResult.Failed(TransferError(ErrorKind.RANGE_MISMATCH, e.message ?: "Range mismatch"), stats())
    } catch (e: StorageException) {
        TransferResult.Failed(storageError(e), stats())
    } catch (e: IOException) {
        TransferResult.Failed(networkError(e), stats())
    } finally {
        withContext(NonCancellable) { runCatching { saveCheckpoint() } }
    }

    private suspend fun runTransfer(): TransferResult {
        val cp = request.checkpoint
        if (cp != null && cp.rangeSupported && cp.totalBytes != null && cp.downloadedBytes > 0 && cp.segments.isNotEmpty()) {
            if (tryResume(cp)) return downloadWithFallback(initial = null)
            listener.onEvent(EngineEventType.RESTARTED_FROM_ZERO, "Previous progress could not be reused safely")
        }
        return fresh()
    }

    // ---------------------------------------------------------------- probing

    private suspend fun open(url: String, range: String?, ifRange: String? = null): Opened {
        val req = Request.Builder().url(url).engineDefaults(request.headers).apply {
            if (range != null) header("Range", range)
            if (ifRange != null) header("If-Range", ifRange)
        }.build()
        val call = client.newCall(req)
        return Opened(call, call.await())
    }

    private fun publishProbe(r: Response) {
        probed = true
        listener.onProbed(ProbeInfo(resolvedUrl, r.code, total, rangeSupported, validators, r.header("Content-Type"), r.header("Content-Disposition")))
    }

    private suspend fun fresh(): TransferResult {
        segs.clear()
        val opened = open(request.url, "bytes=0-")
        val r = opened.response
        resolvedUrl = r.request.url.toString()
        validators = DownloadEngine.validatorsOf(r)
        val cr = ContentRange.parse(r.header("Content-Range"))
        when {
            r.code == 206 && cr?.start == 0L && cr.total != null && cr.total > 0 && isIdentity(r) -> {
                total = cr.total
                rangeSupported = true
                publishProbe(r)
                storage { sink.setLength(cr.total) }
                SegmentPlanner.initialPlan(cr.total, request.maxConnections, request.minSegmentBytes)
                    .forEach { segs += Seg(it.index, it.start, it.end, it.position) }
                return downloadWithFallback(initial = opened)
            }
            r.code == 206 -> { // unusable range answer: retry without ranges
                r.close()
                listener.onEvent(EngineEventType.FALLBACK_SINGLE_CONNECTION, "Unusable Content-Range '${r.header("Content-Range")}'")
                fellBack = true
                return singleStream(null)
            }
            r.isSuccessful -> {
                rangeSupported = false
                total = r.body.contentLength().takeIf { it >= 0 }
                publishProbe(r)
                return singleStream(opened)
            }
            r.code == 416 && cr?.total == 0L -> {
                r.close()
                total = 0
                rangeSupported = false
                publishProbe(r)
                storage { sink.setLength(0) }
                return complete()
            }
            else -> {
                r.close()
                throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
            }
        }
    }

    /** Validates that earlier progress still belongs to the same resource (risks R2, R6, R7, R8). */
    private suspend fun tryResume(cp: Checkpoint): Boolean {
        var opened = open(cp.resolvedUrl, "bytes=0-")
        if (opened.response.code in setOf(401, 403, 404, 410) && cp.resolvedUrl != request.url) {
            opened.response.close()
            opened = open(request.url, "bytes=0-") // pinned mirror expired: re-resolve from the original link
        }
        val r = opened.response
        try {
            if (r.code == 200) {
                listener.onEvent(EngineEventType.RESTARTED_FROM_ZERO, "Server no longer supports ranges")
                return false
            }
            if (r.code != 206) throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
            val cr = ContentRange.parse(r.header("Content-Range"))
            val now = DownloadEngine.validatorsOf(r)
            val same = cp.validators.sameAs(now)
            if (cr?.total != cp.totalBytes || same == false) {
                listener.onEvent(EngineEventType.RESOURCE_CHANGED, "Size or validator changed (was ${cp.totalBytes}, now ${cr?.total})")
                return false
            }
            resolvedUrl = r.request.url.toString()
            validators = if (now.isEmpty) cp.validators else now
            total = cp.totalBytes
            rangeSupported = true
            segs.clear()
            cp.segments.forEach { segs += Seg(it.index, it.start, it.end, it.position) }
        } finally {
            r.close()
        }
        if (cp.validators.sameAs(validators) == null && !overlapMatches()) {
            listener.onEvent(EngineEventType.RESOURCE_CHANGED, "Re-downloaded bytes differ from the saved part")
            return false
        }
        publishProbeAfterResume()
        return true
    }

    private fun publishProbeAfterResume() {
        probed = true
        listener.onProbed(ProbeInfo(resolvedUrl, 206, total, true, validators, null, null))
    }

    /** Without validators, re-fetch the bytes just before a resume point and compare them. */
    private suspend fun overlapMatches(): Boolean {
        val seg = segs.firstOrNull { it.pos.get() - it.start >= DownloadEngine.OVERLAP_CHECK_BYTES } ?: return false
        val to = seg.pos.get() - 1
        val from = to - DownloadEngine.OVERLAP_CHECK_BYTES + 1
        val opened = open(resolvedUrl, "bytes=$from-$to")
        opened.response.use { r ->
            if (RangeValidator.check(r.code, r.header("Content-Range"), r.header("Content-Encoding"), from, to, total) != RangeCheck.Ok) return false
            val remote = r.body.bytes()
            if (remote.size != DownloadEngine.OVERLAP_CHECK_BYTES) return false
            val local = ByteArray(remote.size)
            var read = 0
            while (read < local.size) {
                val n = storage { sink.read(from + read, local, read, local.size - read) }
                if (n <= 0) return false
                read += n
            }
            return remote.contentEquals(local)
        }
    }

    // ---------------------------------------------------------------- transfer

    private suspend fun downloadWithFallback(initial: Opened?): TransferResult = try {
        downloadSegments(initial)
    } catch (e: RangeProblem) {
        initial?.response?.close()
        fellBack = true
        rangeSupported = false
        listener.onEvent(EngineEventType.FALLBACK_SINGLE_CONNECTION, e.message ?: "Range validation failed")
        storage { sink.setLength(0) }
        singleStream(null)
    }

    private suspend fun downloadSegments(initial: Opened?): TransferResult {
        val holder = AtomicReference(initial)
        coroutineScope {
            val ticker = launch { tick() }
            try {
                val workers = request.maxConnections.coerceAtLeast(1)
                (0 until workers).map { async { worker(holder) } }.awaitAll()
            } finally {
                ticker.cancel()
                holder.getAndSet(null)?.response?.close()
            }
        }
        if (segs.any { !it.done }) {
            throw TransferFailure(TransferError(ErrorKind.HTTP, "Server throttled every connection", 429, null, resumed))
        }
        return complete()
    }

    private suspend fun worker(holder: AtomicReference<Opened?>) {
        val active = activeWorkers.incrementAndGet()
        maxWorkers.accumulateAndGet(active) { a, b -> maxOf(a, b) }
        try {
            while (true) {
                val seg = nextSegment() ?: return
                val initial = if (seg.start == 0L && seg.pos.get() == 0L) holder.getAndSet(null) else null
                if (fetchWithRetry(seg, initial) == WorkerOutcome.THROTTLED) return
            }
        } finally {
            activeWorkers.decrementAndGet()
        }
    }

    private suspend fun nextSegment(): Seg? = mutex.withLock {
        segs.firstOrNull { !it.assigned && !it.done }?.let { it.assigned = true; return@withLock it }
        val victim = segs.filter { it.assigned && !it.done }.maxByOrNull { it.remaining } ?: return@withLock null
        val mid = SegmentPlanner.splitPoint(victim.pos.get(), victim.end.get(), request.minSegmentBytes, 2L * DownloadEngine.BUFFER_SIZE)
            ?: return@withLock null
        val stolen = Seg(segs.size, mid, victim.end.get(), mid).also { it.assigned = true }
        victim.end.set(mid - 1)
        segs += stolen
        stolen
    }

    private suspend fun fetchWithRetry(seg: Seg, initial: Opened?): WorkerOutcome {
        var attempt = 0
        var first = initial
        while (!seg.done) {
            val before = seg.pos.get()
            try {
                fetchOnce(seg, first)
                first = null
                if (seg.pos.get() == before && !seg.done) throw IOException("No data received for segment ${seg.index}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: RangeProblem) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: HttpStatusException) {
                first?.response?.close(); first = null
                segmentFailures.incrementAndGet()
                listener.onEvent(EngineEventType.SEGMENT_FAILED, "Segment ${seg.index}: HTTP ${e.code}")
                if ((e.code == 429 || e.code == 503) && activeWorkers.get() > 1) {
                    throttled.set(true)
                    mutex.withLock { seg.assigned = false }
                    listener.onEvent(EngineEventType.CONNECTIONS_REDUCED, "Server throttled (HTTP ${e.code}); continuing with fewer connections")
                    return WorkerOutcome.THROTTLED
                }
                if (!retryableStatus(e.code) || attempt >= request.segmentRetries) throw e
                attempt++
                delay(backoff(attempt, e.retryAfterMillis))
            } catch (e: IOException) {
                first?.response?.close(); first = null
                segmentFailures.incrementAndGet()
                listener.onEvent(EngineEventType.SEGMENT_FAILED, "Segment ${seg.index}: ${e.javaClass.simpleName}")
                if (seg.pos.get() > before) attempt = 0 // progress was made; only count consecutive failures
                if (attempt >= request.segmentRetries) throw e
                attempt++
                delay(backoff(attempt, null))
            }
        }
        return WorkerOutcome.DONE
    }

    private suspend fun fetchOnce(seg: Seg, initial: Opened?) {
        val opened = initial ?: run {
            val from = seg.pos.get(); val to = seg.end.get()
            val o = open(resolvedUrl, "bytes=$from-$to", validators.ifRange)
            val r = o.response
            if (r.code == 200 || r.code == 206) {
                val check = RangeValidator.check(r.code, r.header("Content-Range"), r.header("Content-Encoding"), from, to, total)
                if (check is RangeCheck.Mismatch) { r.close(); throw RangeProblem(check.reason) }
            } else {
                r.close()
                throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
            }
            o
        }
        pump(opened, seg)
    }

    private suspend fun pump(opened: Opened, seg: Seg) = cancellingCall(opened.call) {
        withContext(Dispatchers.IO) {
            opened.response.use { r ->
                val source = r.body.source()
                val buf = ByteArray(DownloadEngine.BUFFER_SIZE)
                while (true) {
                    ensureActive()
                    val pos = seg.pos.get()
                    val remaining = seg.end.get() - pos + 1
                    if (remaining <= 0) break
                    val n = source.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    for (l in limiters) l.acquire(n)
                    storage { sink.write(pos, buf, 0, n) }
                    seg.pos.addAndGet(n.toLong())
                    sessionBytes.addAndGet(n.toLong())
                }
            }
        }
    }

    private suspend fun singleStream(initial: Opened?): TransferResult {
        val opened = initial ?: open(request.url, null)
        val r = opened.response
        if (!r.isSuccessful) {
            r.close()
            throw HttpStatusException(r.code, RetryAfter.parse(r.header("Retry-After")))
        }
        if (initial == null) {
            resolvedUrl = r.request.url.toString()
            validators = DownloadEngine.validatorsOf(r)
            total = r.body.contentLength().takeIf { it >= 0 }
            rangeSupported = false
            if (!probed) publishProbe(r)
        }
        storage { sink.setLength(0) }
        val seg = Seg(0, 0, total?.minus(1) ?: (Long.MAX_VALUE - 1), 0).also { it.assigned = true }
        mutex.withLock { segs.clear(); segs += seg }
        activeWorkers.set(1); maxWorkers.accumulateAndGet(1) { a, b -> maxOf(a, b) }
        coroutineScope {
            val ticker = launch { tick() }
            try { pump(opened, seg) } finally { ticker.cancel(); activeWorkers.set(0) }
        }
        val t = total
        if (t == null) {
            total = seg.pos.get()
            seg.end.set(seg.pos.get() - 1)
        } else if (seg.pos.get() < t) {
            throw IOException("Connection closed after ${seg.pos.get()} of $t bytes")
        }
        return complete()
    }

    // ---------------------------------------------------------------- progress, checkpoint, verification

    private suspend fun tick() {
        var lastCheckpoint = System.nanoTime()
        var lastBytes = downloaded()
        var lastTime = System.nanoTime()
        var speed = 0.0
        while (true) {
            delay(request.progressIntervalMillis)
            val now = System.nanoTime()
            val bytes = downloaded()
            val instant = (bytes - lastBytes) * 1e9 / (now - lastTime).coerceAtLeast(1)
            speed = if (speed == 0.0) instant else 0.3 * instant + 0.7 * speed
            lastBytes = bytes; lastTime = now
            listener.onProgress(bytes, total, speed.toLong(), activeWorkers.get(), snapshot())
            if ((now - lastCheckpoint) / 1_000_000 >= request.checkpointIntervalMillis) {
                saveCheckpoint()
                lastCheckpoint = now
            }
        }
    }

    private fun snapshot(): List<SegmentState> = segs.toList().map { it.state() }
    private fun downloaded(): Long = segs.toList().sumOf { it.state().downloaded }

    /** Positions are read first, then data is flushed, then the checkpoint is stored (risk R3). */
    private suspend fun saveCheckpoint() {
        if (!probed) return
        val states = snapshot()
        storage { sink.sync() }
        listener.onCheckpoint(Checkpoint(total, rangeSupported, validators, resolvedUrl, states))
    }

    private suspend fun complete(): TransferResult {
        val t = total ?: 0L
        listener.onVerifying()
        val states = snapshot()
        if (t > 0 && !SegmentPlanner.coversCompletely(states, t)) {
            throw TransferFailure(TransferError(ErrorKind.INVALID_RESPONSE, "Segments do not cover the whole file"))
        }
        storage {
            if (sink.size() != t) sink.setLength(t)
            sink.sync()
        }
        var hex: String? = null
        request.expectedChecksum?.let { expected ->
            hex = digest(expected.javaAlgorithm, t)
            if (!hex.equals(expected.hex.trim(), ignoreCase = true)) {
                throw TransferFailure(TransferError(ErrorKind.CHECKSUM_MISMATCH, "Expected ${expected.hex}, got $hex"))
            }
        }
        saveCheckpoint()
        listener.onEvent(EngineEventType.VERIFIED, "Size $t bytes${hex?.let { ", checksum $it" } ?: ""}")
        return TransferResult.Completed(t, hex, resolvedUrl, stats())
    }

    private suspend fun digest(algorithm: String, size: Long): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(DownloadEngine.BUFFER_SIZE)
        var pos = 0L
        while (pos < size) {
            ensureActive()
            val n = storage { sink.read(pos, buf, 0, minOf(buf.size.toLong(), size - pos).toInt()) }
            if (n <= 0) break
            md.update(buf, 0, n)
            pos += n
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- helpers

    private fun stats() = TransferStats(
        segments = segs.size,
        segmentFailures = segmentFailures.get(),
        throttled = throttled.get(),
        fellBackToSingle = fellBack,
        connectionsUsed = maxWorkers.get(),
        bytesThisSession = sessionBytes.get(),
        activeMillis = (System.nanoTime() - startedAt) / 1_000_000,
        rangeSupported = rangeSupported,
    )

    private fun backoff(attempt: Int, retryAfter: Long?): Long {
        if (retryAfter != null) return retryAfter.coerceAtMost(30_000)
        val cap = request.retryBaseDelayMillis * (1L shl attempt.coerceAtMost(6))
        return (Random.nextDouble(0.5, 1.0) * cap).toLong().coerceAtMost(60_000)
    }

    private fun retryableStatus(code: Int) = code == 408 || code == 429 || code >= 500

    private fun isIdentity(r: Response): Boolean = r.header("Content-Encoding").let { it == null || it.equals("identity", true) }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: IOException) {
        throw StorageException(e.message ?: "Storage error", e)
    }

    private fun httpError(e: HttpStatusException) = TransferError(ErrorKind.HTTP, "HTTP ${e.code}", e.code, e.retryAfterMillis, resumed)

    private fun networkError(e: IOException) = TransferError(
        if (e is SocketTimeoutException) ErrorKind.TIMEOUT else ErrorKind.NETWORK,
        "${e.javaClass.simpleName}: ${e.message}", resumed = resumed,
    )

    private fun storageError(e: StorageException): TransferError {
        val text = (e.message + " " + e.cause?.message).lowercase()
        val kind = when {
            "enospc" in text || "no space" in text -> ErrorKind.STORAGE_FULL
            "efbig" in text || "file too large" in text -> ErrorKind.FILE_TOO_LARGE
            else -> ErrorKind.STORAGE
        }
        return TransferError(kind, e.message ?: "Storage error")
    }
}
