package com.sdm.engine.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket enforced inside the read loop (spec section 18). Rate changes apply live.
 * A download acquires from its own bucket and from the shared global bucket.
 */
class RateLimiter(bytesPerSecond: Long = 0, private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile var bytesPerSecond: Long = bytesPerSecond
    private val mutex = Mutex()
    private var tokens = 0.0
    private var last = nanoTime()

    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0) {
            mutex.withLock { tokens = 0.0; last = nanoTime() }
            return
        }
        val waitNanos = mutex.withLock {
            val now = nanoTime()
            val burst = maxOf(rate / 4.0, 64.0 * 1024)
            tokens = minOf(burst, tokens + (now - last) * rate / 1e9)
            last = now
            tokens -= bytes
            if (tokens >= 0) 0L else (-tokens * 1e9 / rate).toLong()
        }
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }

    companion object {
        val UNLIMITED get() = RateLimiter(0)
    }
}
