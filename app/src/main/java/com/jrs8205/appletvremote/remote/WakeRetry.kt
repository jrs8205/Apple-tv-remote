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
    /** Recognises an error that waiting will not fix; the first such error ends the loop. */
    private val giveUp: (Exception) -> Boolean = { false },
) {

    /** How many attempts were made and, when the TV never answered, the error of the last one. */
    class Outcome(val attempts: Int, val error: Exception?)

    /**
     * Calls [sendWake] (with the attempt number, starting at 1) and then [connect] until [connect]
     * returns normally, [timeoutMs] has passed, or [connect] fails with an error [giveUp] accepts.
     */
    suspend fun run(sendWake: suspend (attempt: Int) -> Unit, connect: suspend () -> Unit): Outcome {
        val deadline = now() + timeoutMs
        var attempts = 0
        var lastError: Exception? = null
        while (now() < deadline) {
            attempts++
            sendWake(attempts)
            try {
                connect()
                return Outcome(attempts, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (giveUp(e)) return Outcome(attempts, e)
                lastError = e
                delay(retryDelayMs)
            }
        }
        return Outcome(attempts, lastError ?: IllegalStateException("no attempt fitted in ${timeoutMs}ms"))
    }
}
