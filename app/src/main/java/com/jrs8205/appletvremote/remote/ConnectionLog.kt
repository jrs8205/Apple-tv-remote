package com.jrs8205.appletvremote.remote

import android.util.Log
import com.jrs8205.appletvremote.BuildConfig
import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few hundred protocol lines in memory for the log screen and hands every line to
 * [sink] (the log file); logcat only in debug builds.
 */
class ConnectionLog(
    private val sink: ((String) -> Unit)? = null,
    private val logcat: (String) -> Unit = { if (BuildConfig.DEBUG) Log.d(TAG, it) },
) : ProtocolLog {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(message: () -> String) {
        val text = message()
        logcat(text)
        val line = "${synchronized(format) { format.format(Date()) }} $text"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        sink?.invoke(line)
    }

    /** Empties the screen; the file keeps what it has. */
    fun clear() {
        _lines.value = emptyList()
    }

    private companion object {
        const val TAG = "Companion"
        const val MAX_LINES = 300
    }
}
