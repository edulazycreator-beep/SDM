package com.sdm.core.data.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A minimal web page on the local network for sending links from a computer to SDM. It runs only
 * while the user keeps its screen open. Every submission needs the 6-digit pairing code shown in
 * the app; after 5 wrong codes the server stops. Only private-network clients are answered, bodies
 * are limited to 64 KB and nothing is executed: links are only handed to [onLinks].
 */
class LanLinkServer(
    private val bind: InetAddress,
    private val port: Int = 0,
    val code: String = newCode(),
    private val onLinks: suspend (List<String>) -> Int,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    @Volatile var failures = 0; private set
    @Volatile var received = 0; private set
    val localPort: Int get() = socket?.localPort ?: -1

    fun start(scope: CoroutineScope) {
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port)) }
        socket = s
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { client.use { handle(it) } } }
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; job?.cancel() }

    private suspend fun handle(c: Socket) {
        c.soTimeout = 10_000
        if (!isLocal(c.inetAddress)) { respond(c, 403, page("Only devices on the same network can send links.")); return }
        val req = parse(BufferedInputStream(c.getInputStream())) ?: run { respond(c, 400, page("Bad request")); return }
        when {
            req.method == "GET" && (req.path == "/" || req.path.startsWith("/?")) -> respond(c, 200, form(null))
            req.method == "POST" && req.path == "/add" -> {
                val f = form(req.body)
                if (failures >= 5) { respond(c, 429, page("Too many wrong codes. Open \"Send from computer\" in SDM again to get a new code.")); stop(); return }
                if (f["code"]?.trim() != code) { failures++; respond(c, 403, form("Wrong code. Use the 6-digit code shown in SDM.")); return }
                val links = com.sdm.core.common.Urls.extract(f["links"].orEmpty()).take(200)
                if (links.isEmpty()) { respond(c, 200, form("No valid links found. Paste http(s), FTP or SFTP links.")); return }
                val added = onLinks(links)
                received += added
                respond(c, 200, form("Sent ${links.size} link(s) to SDM ($added added)."))
            }
            else -> respond(c, 404, page("Not found"))
        }
    }

    data class Request(val method: String, val path: String, val body: String)

    companion object {
        private const val MAX_BODY = 64 * 1024

        fun newCode(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()

        fun isLocal(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)

        fun parse(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (head.size() < 16 * 1024) {
                val b = input.read(); if (b < 0) return null
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) break
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size < 2) return null
            val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
            return Request(parts[0].uppercase(), parts[1], String(body, 0, read, Charsets.UTF_8))
        }

        fun form(body: String?): Map<String, String> = body.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun page(message: String) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SDM</title>
            <style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;color:#1b1b1f}textarea,input{width:100%;box-sizing:border-box;font:inherit;padding:10px;margin:6px 0 14px;border:1px solid #888;border-radius:8px}button{font:inherit;padding:10px 20px;border-radius:20px;border:0;background:#3657c2;color:#fff}.m{padding:10px;border-radius:8px;background:#eef}</style>
            </head><body><h2>Send links to SDM</h2>${esc(message).let { if (it.isBlank()) "" else "<p class=m>$it</p>" }}</body></html>"""

        fun form(message: String?) = page(message.orEmpty()).replace("</body>", """<form method="post" action="/add">
            <label>Links (one per line)</label><textarea name="links" rows="8" required placeholder="https://…"></textarea>
            <label>Code shown in SDM</label><input name="code" inputmode="numeric" maxlength="6" required autocomplete="off">
            <button type="submit">Send to phone</button></form></body>""")

        private fun respond(c: Socket, status: Int, html: String) {
            val bytes = html.toByteArray(Charsets.UTF_8)
            val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 429 to "Too Many Requests")[status] ?: "OK"
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
                "X-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        }
    }
}
