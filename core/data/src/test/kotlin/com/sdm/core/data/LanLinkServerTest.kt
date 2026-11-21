package com.sdm.core.data

import com.sdm.core.data.lan.LanLinkServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

class LanLinkServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val got = mutableListOf<String>()
    private val server = LanLinkServer(InetAddress.getLoopbackAddress(), code = "123456") { links -> synchronized(got) { got += links }; links.size }.also { it.start(scope) }

    @After fun tearDown() { server.stop(); scope.cancel() }

    private fun post(code: String, links: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${server.localPort}/add").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write("links=${URLEncoder.encode(links, "UTF-8")}&code=$code".toByteArray()) }
        val body = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return c.responseCode to body
    }

    @Test fun acceptsLinksOnlyWithTheCode() {
        val page = (URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()
        assertTrue(page.contains("<form"))
        assertEquals(403, post("000000", "https://a.example/x.zip").first)
        assertTrue(got.isEmpty())
        val (status, body) = post("123456", "https://a.example/x.zip\nnot a link\nftp://b.example/y.iso <script>")
        assertEquals(200, status)
        assertEquals(listOf("https://a.example/x.zip", "ftp://b.example/y.iso"), got)
        assertTrue(body.contains("Sent 2 link(s)") && !body.contains("<script>"))
    }

    @Test fun stopsAfterTooManyWrongCodes() {
        repeat(5) { assertEquals(403, post("111111", "https://a.example/x").first) }
        assertEquals(429, post("123456", "https://a.example/x").first)
        assertTrue(got.isEmpty())
    }

    @Test fun parsesForms() {
        assertEquals(mapOf("a" to "x y", "b" to "&="), LanLinkServer.form("a=x+y&b=%26%3D"))
        assertTrue(LanLinkServer.isLocal(InetAddress.getByName("192.168.1.20")) && !LanLinkServer.isLocal(InetAddress.getByName("8.8.8.8")))
        assertTrue(LanLinkServer.newCode().length == 6)
    }
}
