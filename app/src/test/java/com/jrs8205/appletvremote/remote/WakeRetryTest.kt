package com.jrs8205.appletvremote.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val outcome = retry.run(
            sendWake = { attempt -> events += "wake $attempt" },
            connect = {
                events += "connect"
                if (++attempts < 3) throw IOException("asleep")
            },
        )

        assertEquals(3, outcome.attempts)
        assertNull(outcome.error)
        assertEquals(listOf("wake 1", "connect", "wake 2", "connect", "wake 3", "connect"), events)
        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun givesUpWithTheLastErrorAndTheAttemptCountOnceTheDeadlinePasses() = runTest {
        var wakes = 0
        var attempts = 0
        val retry = WakeRetry(timeoutMs = 5_000, retryDelayMs = 1_000, now = { testScheduler.currentTime })

        val outcome = retry.run(
            sendWake = { wakes++ },
            connect = { throw IOException("still asleep ${++attempts}") },
        )

        assertEquals(5, outcome.attempts)
        assertEquals("still asleep 5", outcome.error?.message)
        assertEquals(attempts, wakes)
        assertTrue("the deadline stops the loop", testScheduler.currentTime >= 5_000)
    }

    @Test
    fun anErrorTheCallerGivesUpOnEndsTheLoopAtOnce() = runTest {
        var wakes = 0
        val refused = IOException("certificate changed")
        val retry = WakeRetry(timeoutMs = 90_000, retryDelayMs = 1_000, now = { testScheduler.currentTime }, giveUp = { it === refused })

        val outcome = retry.run(sendWake = { wakes++ }, connect = { throw refused })

        assertEquals(1, outcome.attempts)
        assertSame(refused, outcome.error)
        assertEquals(1, wakes)
        assertEquals(0, testScheduler.currentTime)
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
