package com.jrs8205.appletvremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLogSinkTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun appendsEveryLineWithTheDateInFront() {
        val sink = FileLogSink(File(folder.root, "logs"))

        sink.append("12:00:00.000 connect failed: boom")
        sink.append("12:00:01.000 connection: Ready")
        sink.flush()

        val lines = sink.file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], Regex("""\d{4}-\d{2}-\d{2} 12:00:00\.000 connect failed: boom""").matches(lines[0]))
        assertTrue(lines[1], Regex("""\d{4}-\d{2}-\d{2} 12:00:01\.000 connection: Ready""").matches(lines[1]))
    }

    @Test
    fun rotatesToThePreviousFileOnceTheLimitIsReached() {
        val sink = FileLogSink(File(folder.root, "logs"), maxBytes = 60)

        sink.append("first line, long enough to count")
        sink.append("second line, long enough to count")
        sink.append("third line, after the rotation")
        sink.flush()

        assertTrue(sink.previous.readText().contains("first line"))
        assertTrue(sink.previous.readText().contains("second line"))
        assertEquals(1, sink.file.readLines().size)
        assertTrue(sink.file.readText().contains("third line"))
    }

    @Test
    fun aSecondRotationDropsTheOldestFile() {
        val sink = FileLogSink(File(folder.root, "logs"), maxBytes = 30)

        sink.append("line one, past the limit")
        sink.append("line two, past the limit")
        sink.append("line three, past the limit")
        sink.flush()

        assertFalse(sink.previous.readText().contains("line one"))
        assertTrue(sink.previous.readText().contains("line two"))
        assertTrue(sink.file.readText().contains("line three"))
    }

    @Test
    fun createsTheDirectoryAndSurvivesAnUnwritableOne() {
        val nested = File(folder.root, "a/b/logs")
        FileLogSink(nested).apply { append("created"); flush() }
        assertTrue(File(nested, "connection.log").readText().contains("created"))

        val blocked = folder.newFile("not-a-directory")
        val sink = FileLogSink(blocked)
        sink.append("dropped")
        sink.flush()
        assertTrue(blocked.isFile)
    }
}
