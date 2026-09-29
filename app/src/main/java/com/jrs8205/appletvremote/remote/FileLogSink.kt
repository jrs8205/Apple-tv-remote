package com.jrs8205.appletvremote.remote

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    private val snapshotDirectory: File get() = File(directory, "share")
    private val snapshots = AtomicInteger(0)

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

    /**
     * Copies the files as they are right now into [snapshotDirectory] and returns the copies, newest
     * first. The copy is made on the writer thread, so no line lands and no rotation happens halfway
     * through, and a copy stays as it is while the live files move on. The previous snapshot is removed.
     */
    fun snapshot(): List<File> = executor.submit<List<File>> {
        runCatching {
            snapshotDirectory.mkdirs()
            snapshotDirectory.listFiles()?.forEach { it.delete() }
            val stamp = "${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${snapshots.incrementAndGet()}"
            listOf(file to "connection-$stamp.log", previous to "connection-$stamp.1.log").mapNotNull { (source, name) ->
                if (!source.isFile) return@mapNotNull null
                File(snapshotDirectory, name).also { source.copyTo(it, overwrite = true) }
            }
        }.getOrDefault(emptyList())
    }.get()

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
