package com.jrs8205.appletvremote.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WakeRetryTest {

    @Test
    fun sendsTheWakeSignalBeforeEveryAttemptUntilTheTvAnswers() = runTest {
        val events = ArrayList<String>()
        var attempts = 0
        val retry = WakeRetry(timeoutMs = 90_000, retryDelayMs = 1_000, now = { testScheduler.currentTime })

        val result = retry.run(
            sendWake = { events += "wake" },
            connect = {
                events += "connect"
                if (++attempts < 3) throw IOException("asleep")
            },
        )

        assertEquals(3, result.getOrThrow())
        assertEquals(listOf("wake", "connect", "wake", "connect", "wake", "connect"), events)
        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun givesUpWithTheLastErrorOnceTheDeadlinePasses() = runTest {
        var wakes = 0
        var attempts = 0
        val retry = WakeRetry(timeoutMs = 5_000, retryDelayMs = 1_000, now = { testScheduler.currentTime })

        val result = retry.run(
            sendWake = { wakes++ },
            connect = { throw IOException("still asleep ${++attempts}") },
        )

        assertTrue(result.isFailure)
        assertEquals("still asleep 5", result.exceptionOrNull()?.message)
        assertEquals(attempts, wakes)
        assertTrue("the deadline stops the loop", testScheduler.currentTime >= 5_000)
    }

    @Test
    fun cancellationStopsTheLoopAtOnce() {
        var wakes = 0
        val cancelled = CancellationException("screen closed")
        val retry = WakeRetry(timeoutMs = 90_000, retryDelayMs = 1_000)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { retry.run(sendWake = { wakes++ }, connect = { throw cancelled }) }
        }

        assertSame(cancelled, thrown)
        assertEquals(1, wakes)
    }
}
