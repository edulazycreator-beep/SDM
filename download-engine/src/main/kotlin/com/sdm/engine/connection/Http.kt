package com.sdm.engine.connection

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes a call; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Runs blocking body reads so that coroutine cancellation immediately cancels the socket. */
@OptIn(InternalCoroutinesApi::class)
suspend fun <T> cancellingCall(call: Call, block: suspend () -> T): T {
    val handle = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
    try {
        return block()
    } finally {
        handle.dispose()
    }
}

fun Request.Builder.engineDefaults(headers: Map<String, String>): Request.Builder {
    headers.forEach { (k, v) ->
        if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true) && !k.equals("If-Range", true)) header(k, v)
    }
    // Transparent gzip would make byte counts and ranges meaningless (risk R1).
    header("Accept-Encoding", "identity")
    return this
}

/** Client tuned for segmented transfers: HTTP/1.1 so each segment gets its own TCP connection (risk R5). */
fun OkHttpClient.forSegments(): OkHttpClient = newBuilder()
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
    .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 })
    .retryOnConnectionFailure(true)
    .build()

fun defaultEngineClient(connectTimeoutSeconds: Long = 20, readTimeoutSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

object RetryAfter {
    /** Parses delta-seconds or an HTTP-date into milliseconds from now. */
    fun parse(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val v = value?.trim() ?: return null
        v.toLongOrNull()?.let { return (it.coerceAtLeast(0)) * 1000 }
        return runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis }
            .getOrNull()?.coerceAtLeast(0)
    }
}
