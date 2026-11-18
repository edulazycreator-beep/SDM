package com.sdm.engine.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.sdm.engine.Checkpoint
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
import com.sdm.engine.storage.RandomAccessSink
import com.sdm.engine.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Login for FTP/SFTP, carried in the (encrypted) request headers as HTTP Basic credentials. */
data class RemoteLogin(val user: String, val password: String) {
    fun header(): Pair<String, String> = "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    companion object {
        fun from(headers: Map<String, String>): RemoteLogin? {
            val v = headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value ?: return null
            if (!v.startsWith("Basic ", true)) return null
            val text = runCatching { String(Base64.getDecoder().decode(v.substring(6).trim())) }.getOrNull() ?: return null
            return RemoteLogin(text.substringBefore(':'), text.substringAfter(':', ""))
        }

        /** Splits `ftp://user:pass@host/path` into the URL without credentials and the login. */
        fun split(url: String): Pair<String, RemoteLogin?> {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return url to null
            val info = u.rawUserInfo ?: return url to null
            val clean = URI(u.scheme, null, u.host, u.port, u.path, u.query, u.fragment).toString()
            val user = URLDecoder.decode(info.substringBefore(':'), "UTF-8")
            val pass = URLDecoder.decode(info.substringAfter(':', ""), "UTF-8")
            return clean to RemoteLogin(user, pass)
        }
    }
}

/**
 * Downloads one file over FTP, FTPS (explicit TLS) or SFTP with resume. One connection per file;
 * the same checkpoint, progress and result types as the HTTP engine so the rest of SDM treats it
 * the same way. SFTP host keys are remembered on first use and a changed key is refused.
 */
class RemoteFileEngine(
    private val knownHosts: File?,
    private val globalLimiter: RateLimiter? = null,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    data class RemoteInfo(val size: Long?, val modified: String?, val name: String)

    private interface Session : Closeable {
        fun info(): RemoteInfo
        fun open(offset: Long): InputStream
        fun finish() {}
    }

    suspend fun probe(url: String, headers: Map<String, String>): Result<RemoteInfo> = withContext(Dispatchers.IO) {
        runCatching { connect(url, headers).use { it.info() } }
    }

    suspend fun run(request: TransferRequest, sink: RandomAccessSink, limiter: RateLimiter, listener: TransferListener): TransferResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var session = 0L
        fun stats() = TransferStats(1, 0, false, false, 1, session, (System.nanoTime() - started) / 1_000_000, true)
        try {
            connect(request.url, request.headers).use { s ->
                val info = s.info()
                val validators = Validators(etag = null, lastModified = info.modified)
                val cp = request.checkpoint
                var position = cp?.downloadedBytes ?: 0L
                if (cp != null && (cp.totalBytes != info.size || (cp.validators.lastModified != null && cp.validators.lastModified != info.modified))) {
                    listener.onEvent(com.sdm.engine.EngineEventType.RESOURCE_CHANGED, "The file changed on the server; starting over")
                    position = 0
                }
                if (info.size != null && position > info.size) position = 0
                listener.onProbed(ProbeInfo(request.url, 200, info.size, true, validators, null, null))
                storage { sink.setLength(position) }
                val total = info.size
                fun segment() = SegmentState(0, 0, (total ?: Long.MAX_VALUE) - 1, position)
                fun checkpoint() = Checkpoint(total, true, validators, request.url, listOf(segment()))
                val buf = ByteArray(64 * 1024)
                var lastProgress = 0L; var lastCheckpoint = System.currentTimeMillis()
                var windowBytes = 0L; var windowStart = System.nanoTime(); var speed = 0L
                s.open(position).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        limiter.acquire(n); globalLimiter?.acquire(n)
                        storage { sink.write(position, buf, 0, n) }
                        position += n; session += n; windowBytes += n
                        val now = System.currentTimeMillis()
                        val elapsed = System.nanoTime() - windowStart
                        if (elapsed >= 1_000_000_000L) { speed = windowBytes * 1_000_000_000L / elapsed; windowBytes = 0; windowStart = System.nanoTime() }
                        if (now - lastProgress >= request.progressIntervalMillis) { lastProgress = now; listener.onProgress(position, total, speed, 1, listOf(segment())) }
                        if (now - lastCheckpoint >= request.checkpointIntervalMillis) { lastCheckpoint = now; storage { sink.sync() }; listener.onCheckpoint(checkpoint()) }
                    }
                }
                s.finish()
                storage { sink.sync() }
                listener.onCheckpoint(checkpoint())
                if (total != null && position != total) throw IOException("Connection closed early: got $position of $total bytes")
                listener.onVerifying()
                val hex = request.expectedChecksum?.let { expected ->
                    val md = java.security.MessageDigest.getInstance(expected.algorithm.uppercase().let { if (it == "SHA256") "SHA-256" else if (it == "SHA1") "SHA-1" else it })
                    var p = 0L
                    while (p < position) { val n = storage { sink.read(p, buf, 0, minOf(buf.size.toLong(), position - p).toInt()) }; if (n <= 0) break; md.update(buf, 0, n); p += n }
                    md.digest().joinToString("") { "%02x".format(it) }.also { actual ->
                        if (!actual.equals(expected.hex.trim(), true)) return@withContext TransferResult.Failed(TransferError(ErrorKind.CHECKSUM_MISMATCH, "Checksum mismatch: expected ${expected.hex}, got $actual"), stats())
                    }
                }
                TransferResult.Completed(position, hex, request.url, stats())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            val t = (e.message + " " + e.cause?.message).lowercase()
            TransferResult.Failed(TransferError(if ("enospc" in t || "no space" in t) ErrorKind.STORAGE_FULL else ErrorKind.STORAGE, e.message ?: "Storage error"), stats())
        } catch (e: RemoteRefused) {
            TransferResult.Failed(TransferError(e.kind, e.message ?: "Refused", e.code), stats())
        } catch (e: SocketTimeoutException) {
            TransferResult.Failed(TransferError(ErrorKind.TIMEOUT, "Timeout: ${e.message}"), stats())
        } catch (e: IOException) {
            TransferResult.Failed(TransferError(ErrorKind.NETWORK, "${e.javaClass.simpleName}: ${e.message}"), stats())
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            TransferResult.Failed(when {
                "Auth" in m || "auth" in m -> TransferError(ErrorKind.HTTP, "Login failed: check the user name and password", 401)
                "HostKey" in m || "reject HostKey" in m -> TransferError(ErrorKind.UNSUPPORTED, "The server's identity changed since the last visit; refusing to connect")
                else -> TransferError(ErrorKind.NETWORK, "SFTP: $m")
            }, stats())
        }
    }

    private class RemoteRefused(val kind: ErrorKind, message: String, val code: Int? = null) : IOException(message)

    private inline fun <T> storage(block: () -> T): T = try { block() } catch (e: StorageException) { throw e } catch (e: IOException) { throw StorageException(e.message ?: "Storage error", e) }

    private fun connect(url: String, headers: Map<String, String>): Session {
        val u = URI(url)
        val login = RemoteLogin.from(headers)
        val path = URLDecoder.decode(u.rawPath ?: "/", "UTF-8").ifBlank { "/" }
        val name = path.substringAfterLast('/').ifBlank { "download" }
        return when (u.scheme?.lowercase()) {
            "ftp", "ftps" -> ftp(u, login, path, name, tls = u.scheme.equals("ftps", true))
            "sftp" -> sftp(u, login, path, name)
            else -> throw RemoteRefused(ErrorKind.UNSUPPORTED, "Unsupported protocol ${u.scheme}")
        }
    }

    private fun ftp(u: URI, login: RemoteLogin?, path: String, name: String, tls: Boolean): Session {
        val c = if (tls) FTPSClient(false) else FTPClient()
        c.connectTimeout = connectTimeoutMillis
        c.setDefaultTimeout(readTimeoutMillis)
        c.controlEncoding = "UTF-8"
        c.connect(u.host, if (u.port > 0) u.port else 21)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) { runCatching { c.disconnect() }; throw RemoteRefused(ErrorKind.NETWORK, "FTP server refused the connection (${c.replyCode})") }
        if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }
        if (!c.login(login?.user ?: "anonymous", login?.password ?: "sdm@localhost")) {
            runCatching { c.disconnect() }
            throw RemoteRefused(ErrorKind.HTTP, if (login == null) "This FTP server needs a user name and password" else "Login failed: check the user name and password", 401)
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.soTimeout = readTimeoutMillis
        return object : Session {
            override fun info(): RemoteInfo {
                val f = runCatching { c.mlistFile(path) }.getOrNull()
                val size = f?.size?.takeIf { it >= 0 } ?: runCatching { c.getSize(path)?.trim()?.toLongOrNull() }.getOrNull()
                if (size == null && f == null && c.listFiles(path).isNullOrEmpty()) throw RemoteRefused(ErrorKind.HTTP, "File not found on the FTP server", 404)
                val modified = runCatching { c.getModificationTime(path) }.getOrNull()?.trim()
                return RemoteInfo(size, modified, name)
            }
            override fun open(offset: Long): InputStream {
                c.restartOffset = offset
                return c.retrieveFileStream(path) ?: throw RemoteRefused(ErrorKind.HTTP, "FTP server refused the file (${c.replyString?.trim()})", if (c.replyCode == 550) 404 else null)
            }
            override fun finish() { c.completePendingCommand() }
            override fun close() { runCatching { c.logout() }; runCatching { c.disconnect() } }
        }
    }

    private fun sftp(u: URI, login: RemoteLogin?, path: String, name: String): Session {
        val jsch = JSch()
        knownHosts?.let { f -> f.parentFile?.mkdirs(); if (!f.exists()) f.createNewFile(); jsch.setKnownHosts(f.path) }
        val s = jsch.getSession(login?.user ?: throw RemoteRefused(ErrorKind.HTTP, "SFTP needs a user name and password", 401), u.host, if (u.port > 0) u.port else 22)
        s.setPassword(login.password)
        s.setConfig("StrictHostKeyChecking", if (knownHosts != null) "accept-new" else "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.timeout = readTimeoutMillis
        s.connect(connectTimeoutMillis)
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(connectTimeoutMillis)
        return object : Session {
            override fun info(): RemoteInfo = try {
                val a = ch.stat(path)
                if (a.isDir) throw RemoteRefused(ErrorKind.INVALID_RESPONSE, "This is a folder, not a file")
                RemoteInfo(a.size, a.mTime.toString(), name)
            } catch (e: SftpException) {
                throw RemoteRefused(ErrorKind.HTTP, if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) "File not found on the SFTP server" else "SFTP: ${e.message}", if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) 404 else 403)
            }
            override fun open(offset: Long): InputStream = ch.get(path, null, offset)
            override fun close() { runCatching { ch.disconnect() }; runCatching { s.disconnect() } }
        }
    }

    companion object {
        val schemes = setOf("ftp", "ftps", "sftp")
        fun handles(url: String): Boolean = runCatching { URI(url.trim()).scheme?.lowercase() in schemes }.getOrDefault(false)
    }
}
