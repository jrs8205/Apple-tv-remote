package com.jrs8205.appletvremote.service.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackGraceTest {

    @Test
    fun aMomentWithoutPlaybackIsNotPassedOn() = runTest {
        val playing = flow {
            emit(true)
            delay(1_000)
            emit(false)
            delay(39)
            emit(true)
            delay(60_000)
        }

        assertEquals(listOf(true), playing.withDropGrace(10_000).toList())
    }

    @Test
    fun playbackThatStaysGoneIsPassedOnOnceTheGraceHasRunOut() = runTest {
        val playing = flow {
            emit(true)
            delay(1_000)
            emit(false)
            delay(20_000)
        }

        val seen = playing.withDropGrace(10_000).map { it to testScheduler.currentTime }.toList()

        assertEquals(listOf(true to 0L, false to 11_000L), seen)
    }
}
