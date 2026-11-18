package com.sdm.engine

import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.remote.RemoteFileEngine
import com.sdm.engine.remote.RemoteLogin
import com.sdm.engine.storage.FileSink
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class RemoteFileEngineTest {
    private val root = Files.createTempDirectory("sdm-remote").toFile()
    private val body = java.util.Random(3).let { r -> ByteArray(700_000).also { r.nextBytes(it) } }
    private val outputs = mutableListOf<File>()
    private lateinit var ftp: org.apache.ftpserver.FtpServer
    private var ftpPort = 0
    private lateinit var ssh: SshServer

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Before fun setUp() {
        File(root, "pub").mkdirs()
        File(root, "pub/file.bin").writeBytes(body)
        ftpPort = freePort()
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply { port = ftpPort }.createListener())
        factory.userManager.save(BaseUser().apply { name = "sdm"; password = "s3cret"; homeDirectory = root.path; authorities = listOf(WritePermission()) })
        ftp = factory.createServer().also { it.start() }
        ssh = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(root, "hostkey.ser").toPath())
            setPasswordAuthenticator { u, p, _ -> u == "sdm" && p == "s3cret" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
    }

    @After fun tearDown() { runCatching { ftp.stop() }; runCatching { ssh.stop(true) }; root.deleteRecursively(); outputs.forEach { it.delete() } }

    private fun temp() = File.createTempFile("remote", ".bin").also { outputs += it }
    private val login = mapOf(RemoteLogin("sdm", "s3cret").header())

    private fun run(engine: RemoteFileEngine, url: String, f: File, headers: Map<String, String> = login, checkpoint: Checkpoint? = null, checksum: ExpectedChecksum? = null) = runBlocking {
        FileSink(f).use { engine.run(TransferRequest(url, headers, checkpoint = checkpoint, expectedChecksum = checksum), it, RateLimiter(), object : TransferListener {}) }
    }

    @Test fun ftpDownloadAndResume() {
        val engine = RemoteFileEngine(null)
        val f = temp()
        val r = run(engine, "ftp://127.0.0.1:$ftpPort/pub/file.bin", f)
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(body, f.readBytes())
        // Resume from the middle: keep the first 300 000 bytes, ask for the rest.
        val g = temp(); g.writeBytes(body.copyOf(300_000))
        val info = runBlocking { engine.probe("ftp://127.0.0.1:$ftpPort/pub/file.bin", login) }.getOrThrow()
        val cp = Checkpoint(body.size.toLong(), true, Validators(lastModified = info.modified), "x", listOf(SegmentState(0, 0, body.size - 1L, 300_000)))
        val r2 = run(engine, "ftp://127.0.0.1:$ftpPort/pub/file.bin", g, checkpoint = cp)
        assertTrue("$r2", r2 is TransferResult.Completed && r2.stats.bytesThisSession == body.size - 300_000L)
        assertArrayEquals(body, g.readBytes())
    }

    @Test fun ftpWrongPasswordAndMissingFile() {
        val engine = RemoteFileEngine(null)
        val bad = run(engine, "ftp://127.0.0.1:$ftpPort/pub/file.bin", temp(), mapOf(RemoteLogin("sdm", "nope").header()))
        assertTrue(bad is TransferResult.Failed && bad.error.httpStatus == 401)
        val missing = run(engine, "ftp://127.0.0.1:$ftpPort/pub/none.bin", temp())
        assertTrue("$missing", missing is TransferResult.Failed)
    }

    @Test fun sftpDownloadWithChecksumAndKnownHost() {
        val known = File(root, "known_hosts")
        val engine = RemoteFileEngine(known)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val f = temp()
        val r = run(engine, "sftp://127.0.0.1:${ssh.port}/pub/file.bin", f, checksum = ExpectedChecksum("SHA-256", sha))
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(body, f.readBytes())
        assertTrue("host key remembered", known.readText().isNotBlank())
        // A different server key for the same host is refused (possible impostor).
        known.writeText(known.readLines().joinToString("\n") { l -> l.split('\t').let { "${it[0]}\t${it[1]}\t" + java.util.Base64.getEncoder().encodeToString(ByteArray(51) { 7 }) } } + "\n")
        val impostor = run(engine, "sftp://127.0.0.1:${ssh.port}/pub/file.bin", temp())
        assertTrue("$impostor", impostor is TransferResult.Failed && impostor.error.kind == ErrorKind.UNSUPPORTED)
        val wrong = run(engine, "sftp://127.0.0.1:${ssh.port}/pub/file.bin", temp(), checksum = ExpectedChecksum("SHA-256", "00"))
        assertTrue(wrong is TransferResult.Failed && wrong.error.kind == ErrorKind.CHECKSUM_MISMATCH)
    }

    @Test fun credentialsAreSplitFromUrl() {
        val (clean, l) = RemoteLogin.split("ftp://me%40x:p%3Ass@host.example:2121/a/b.zip")
        assertEquals("ftp://host.example:2121/a/b.zip", clean)
        assertEquals(RemoteLogin("me@x", "p:ss"), l)
        assertEquals(l, RemoteLogin.from(mapOf(l!!.header())))
        assertTrue(RemoteFileEngine.handles("sftp://h/x") && !RemoteFileEngine.handles("https://h/x"))
    }
}
