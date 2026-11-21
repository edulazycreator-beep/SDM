package com.sdm.core.data.network

import android.os.Build
import com.sdm.core.data.di.AppScope
import com.sdm.core.data.settings.AppSettings
import com.sdm.core.data.settings.DnsMode
import com.sdm.core.data.settings.ProxyType
import com.sdm.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Rebuilds the OkHttp client whenever network settings change (spec section 19). */
@Singleton
class HttpClientProvider @Inject constructor(settings: SettingsRepository, @AppScope scope: CoroutineScope) {
    private data class Key(val connect: Int, val read: Int, val ua: String, val dns: DnsMode, val proxy: ProxyType, val host: String, val port: Int)

    @Volatile private var client: OkHttpClient = build(Key(20, 30, "", DnsMode.SYSTEM, ProxyType.NONE, "", 0))

    init {
        scope.launch {
            settings.settings.map { it.key() }.distinctUntilChanged().collect { client = build(it) }
        }
    }

    fun get(): OkHttpClient = client

    private fun AppSettings.key() = Key(connectTimeoutSeconds, readTimeoutSeconds, userAgent, dnsMode, proxyType, proxyHost, proxyPort)

    private fun build(k: Key): OkHttpClient {
        val ua = k.ua.ifBlank { DEFAULT_USER_AGENT }
        val b = OkHttpClient.Builder()
            .connectTimeout(k.connect.toLong(), TimeUnit.SECONDS)
            .readTimeout(k.read.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", ua).build() else req)
            }
        if (k.proxy != ProxyType.NONE && k.host.isNotBlank()) {
            val type = if (k.proxy == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            b.proxy(Proxy(type, InetSocketAddress.createUnresolved(k.host, k.port)))
        }
        if (k.dns != DnsMode.SYSTEM && k.proxy == ProxyType.NONE) b.dns(doh(k.dns))
        return b.build()
    }

    private fun doh(mode: DnsMode): Dns {
        val bootstrap = OkHttpClient.Builder().build()
        return when (mode) {
            DnsMode.CLOUDFLARE -> DnsOverHttps.Builder().client(bootstrap).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")).build()
            DnsMode.GOOGLE -> DnsOverHttps.Builder().client(bootstrap).url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")).build()
            DnsMode.SYSTEM -> Dns.SYSTEM
        }
    }

    companion object {
        val DEFAULT_USER_AGENT = "SDM/1.0 (Linux; Android ${Build.VERSION.RELEASE}) SmartDownloadManager"
    }
}
