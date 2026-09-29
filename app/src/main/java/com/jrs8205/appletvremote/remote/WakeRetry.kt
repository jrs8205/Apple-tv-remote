package com.jrs8205.appletvremote.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Keeps trying to reach a TV that has been told to wake up. The wake signal goes out again before
 * every attempt: on a Wi-Fi mesh the phone's ARP lookup of a sleeping TV succeeds only now and then,
 * and a packet sent while the lookup is failing never leaves the phone, so a single burst at the
 * start is not enough. A TV that is already awake ignores the repeated packets, so on a plain
 * network the loop ends on the first attempt as before.
 */
internal class WakeRetry(
    private val timeoutMs: Long,
    private val retryDelayMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Calls [sendWake] and then [connect] until [connect] returns normally or [timeoutMs] has passed.
     * Success carries the number of attempts it took; failure carries the last error from [connect].
     */
    suspend fun run(sendWake: suspend () -> Unit, connect: suspend () -> Unit): Result<Int> {
        val deadline = now() + timeoutMs
        var attempts = 0
        var lastError: Exception? = null
        while (now() < deadline) {
            attempts++
            sendWake()
            try {
                connect()
                return Result.success(attempts)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                delay(retryDelayMs)
            }
        }
        return Result.failure(lastError ?: IllegalStateException("no attempt fitted in ${timeoutMs}ms"))
    }
}
