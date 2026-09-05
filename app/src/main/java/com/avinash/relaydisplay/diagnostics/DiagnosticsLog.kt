package com.avinash.relaydisplay.diagnostics

import android.util.Log
import com.avinash.relaydisplay.BuildConfig
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A bounded, in-memory event log for the Diagnostics screen.
 *
 * Two rules make this safe to export from the sharesheet:
 *  - callers pass short static messages plus bounded numbers, never peer content, and
 *  - [redactHost] and [redactPayloadSize] exist so the few places that do want to mention an
 *    endpoint or a payload have an obvious safe way to do it.
 *
 * Nothing here writes to disk: the log dies with the process, so a crash dump or a stolen phone
 * yields no history.
 */
class DiagnosticsLog(private val capacity: Int = MAX_ENTRIES) {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(capacity)
    private val _events = MutableStateFlow<List<Entry>>(emptyList())

    val events: StateFlow<List<Entry>> = _events.asStateFlow()

    fun record(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message.take(MAX_MESSAGE_CHARS))
        synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
            _events.value = entries.toList()
        }
        if (BuildConfig.DEBUG) {
            val priority = when (level) {
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARN -> Log.WARN
                Level.ERROR -> Log.ERROR
            }
            Log.println(priority, "Relay/$tag", entry.message)
        }
    }

    fun debug(tag: String, message: String) = record(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = record(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = record(Level.WARN, tag, message)
    fun error(tag: String, message: String) = record(Level.ERROR, tag, message)

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _events.value = emptyList()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    companion object {
        const val MAX_ENTRIES = 300
        const val MAX_MESSAGE_CHARS = 200

        /** "192.168.1.42" -> "192.168.1.x". Enough to debug a subnet, not enough to identify a host. */
        fun redactHost(host: String?): String {
            if (host.isNullOrEmpty()) return "none"
            val lastDot = host.lastIndexOf('.')
            return if (lastDot > 0) host.substring(0, lastDot + 1) + "x" else "host"
        }

        /** Sizes are fine to log; contents never are. */
        fun redactPayloadSize(bytes: Long): String = "${bytes}B"
    }
}
