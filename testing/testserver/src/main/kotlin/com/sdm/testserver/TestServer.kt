package com.sdm.testserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Programmable HTTP server for engine tests (spec section 31): switchable Range support, lying
 * Content-Range, throttling, mid-body disconnects, redirects, scripted error statuses with
 * Retry-After, cookie-protected files and resources that change between requests.
 */
class TestServer : Closeable {
    class Resource(
        @Volatile var body: ByteArray,
        @Volatile var etag: String? = "\"v1\"",
        @Volatile var lastModified: String? = "Wed, 18 Nov 2026 06:00:00 GMT",
        @Volatile var supportsRanges: Boolean = true,
        /** Shifts the advertised Content-Range start, to simulate a broken server. */
        @Volatile var lieAboutRangeBy: Long = 0,
        @Volatile var bytesPerSecond: Long = 0,
        @Volatile var chunked: Boolean = false,
        @Volatile var requiredCookie: String? = null,
        @Volatile var contentType: String = "application/octet-stream",
        @Volatile var contentDisposition: String? = null,
    ) {
        /** Scripted statuses consumed one per request before normal handling. */
        val scriptedStatuses = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String?>>()
        /** Number of upcoming body responses that drop the connection after this many bytes. */
        @Volatile var disconnectAfterBytes: Long = 0
        val disconnectsRemaining = AtomicInteger(0)
    }

    data class Recorded(val method: String, val path: String, val headers: Map<String, String>)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val resources = ConcurrentHashMap<String, Resource>()
    private val redirects = ConcurrentHashMap<String, String>()
    val requests = CopyOnWriteArrayList<Recorded>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { ex.close() } } }
        server.start()
    }

    val port: Int get() = server.address.port
    fun url(path: String) = "http://127.0.0.1:$port$path"

    fun serve(path: String, resource: Resource): Resource = resource.also { resources[path] = it }
    fun redirect(from: String, to: String) { redirects[from] = to }
    fun requestsFor(path: String) = requests.filter { it.path == path }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        requests += Recorded(ex.requestMethod, path, headers)
        redirects[path]?.let { target ->
            ex.responseHeaders.add("Location", target)
            ex.sendResponseHeaders(302, -1)
            ex.close()
            return
        }
        val res = resources[path] ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        res.scriptedStatuses.poll()?.let { (status, retryAfter) ->
            retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
            ex.sendResponseHeaders(status, -1)
            ex.close()
            return
        }
        if (res.requiredCookie != null && headers["cookie"]?.contains(res.requiredCookie!!) != true) {
            ex.sendResponseHeaders(403, -1); ex.close(); return
        }
        val body = res.body
        val size = body.size.toLong()
        res.etag?.let { ex.responseHeaders.add("ETag", it) }
        res.lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
        ex.responseHeaders.add("Content-Type", res.contentType)
        res.contentDisposition?.let { ex.responseHeaders.add("Content-Disposition", it) }
        if (res.supportsRanges) ex.responseHeaders.add("Accept-Ranges", "bytes")

        val range = headers["range"]
        val ifRange = headers["if-range"]
        val rangeAllowed = res.supportsRanges && range != null && (ifRange == null || ifRange == res.etag || ifRange == res.lastModified)
        if (rangeAllowed) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range!!)
            if (m != null) {
                val start = m.groupValues[1].toLong()
                val end = (m.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                if (start >= size) {
                    ex.responseHeaders.add("Content-Range", "bytes */$size")
                    ex.sendResponseHeaders(416, -1); ex.close(); return
                }
                val shown = start + res.lieAboutRangeBy
                ex.responseHeaders.add("Content-Range", "bytes $shown-${end + res.lieAboutRangeBy}/$size")
                send(ex, res, 206, body, start, end)
                return
            }
        }
        send(ex, res, 200, body, 0, size - 1)
    }

    private fun send(ex: HttpExchange, res: Resource, status: Int, body: ByteArray, start: Long, end: Long) {
        val length = end - start + 1
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(status, -1); ex.close(); return }
        ex.sendResponseHeaders(status, if (res.chunked) 0 else if (length == 0L) -1 else length)
        val dropAfter = if (res.disconnectsRemaining.get() > 0 && res.disconnectsRemaining.getAndDecrement() > 0) res.disconnectAfterBytes else -1
        val out = ex.responseBody
        var pos = start
        var sent = 0L
        val chunk = 8 * 1024
        try {
            while (pos <= end) {
                val n = minOf(chunk.toLong(), end - pos + 1).toInt()
                if (dropAfter >= 0 && sent + n > dropAfter) {
                    out.write(body, pos.toInt(), (dropAfter - sent).toInt().coerceAtLeast(0))
                    out.flush()
                    throw java.io.IOException("simulated disconnect")
                }
                out.write(body, pos.toInt(), n)
                pos += n; sent += n
                if (res.bytesPerSecond > 0) Thread.sleep(n * 1000L / res.bytesPerSecond)
            }
            out.close()
        } catch (e: Exception) {
            // Closing the exchange without finishing the declared length makes the client see a truncated body.
            runCatching { ex.close() }
        }
    }

    override fun close() = server.stop(0)

    companion object {
        fun randomBytes(size: Int, seed: Int = 7): ByteArray = java.util.Random(seed.toLong()).let { r -> ByteArray(size).also { r.nextBytes(it) } }
    }
}
