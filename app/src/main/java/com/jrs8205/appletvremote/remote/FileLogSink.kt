package com.jrs8205.appletvremote.remote

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Appends connection log lines to a file so the reason for a failure survives the process. The
 * current file rotates to [previous] once it reaches [maxBytes]; the file before that is dropped,
 * which keeps the log at two files at most. Writes happen on one background thread and never fail
 * the caller: a log that cannot be written is simply lost.
 */
class FileLogSink(private val directory: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    val file: File get() = File(directory, "connection.log")
    val previous: File get() = File(directory, "connection.1.log")

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "connection-log").apply { isDaemon = true } }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Queues [line] for the file, prefixed with today's date; the line itself carries the time. */
    fun append(line: String) {
        executor.execute { write(line) }
    }

    /** Returns once every line queued so far is in the file. */
    fun flush() {
        executor.submit {}.get()
    }

    /** The files that exist, newest first. */
    fun files(): List<File> = listOf(file, previous).filter { it.isFile }

    private fun write(line: String) {
        runCatching {
            directory.mkdirs()
            if (file.length() >= maxBytes) {
                previous.delete()
                file.renameTo(previous)
            }
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                writer.write(dateFormat.format(Date()))
                writer.write(" ")
                writer.write(line)
                writer.write("\n")
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024
    }
}
