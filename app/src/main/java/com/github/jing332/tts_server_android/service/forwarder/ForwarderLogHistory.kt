package com.github.jing332.tts_server_android.service.forwarder

import com.github.jing332.common.LogEntry

object ForwarderLogHistory {
    private const val MAX_SIZE = 60
    private val logs = mutableListOf<LogEntry>()

    @Synchronized
    fun add(log: LogEntry) {
        logs.add(log)
        val overflow = logs.size - MAX_SIZE
        if (overflow > 0) {
            repeat(overflow) {
                if (logs.isNotEmpty()) logs.removeAt(0)
            }
        }
    }

    @Synchronized
    fun snapshot(): List<LogEntry> {
        return logs.toList()
    }

    @Synchronized
    fun clear() {
        logs.clear()
    }
}
