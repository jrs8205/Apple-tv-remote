package com.jrs8205.appletvremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogTest {

    @Test
    fun handsEveryLineToTheSinkAndKeepsItInMemory() {
        val sunk = ArrayList<String>()
        val log = ConnectionLog(sink = sunk::add, logcat = {})

        log.log { "connect failed: boom" }
        log.log { "connection: Ready" }

        assertEquals(2, sunk.size)
        assertTrue(sunk[0], Regex("""\d{2}:\d{2}:\d{2}\.\d{3} connect failed: boom""").matches(sunk[0]))
        assertEquals(sunk, log.lines.value)
    }

    @Test
    fun clearingTheScreenDoesNotTakeBackWhatTheSinkGot() {
        val sunk = ArrayList<String>()
        val log = ConnectionLog(sink = sunk::add, logcat = {})

        log.log { "kept on disk" }
        log.clear()

        assertTrue(log.lines.value.isEmpty())
        assertEquals(1, sunk.size)
    }
}
