package com.github.jing332.tts_server_android.compose.systts

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.utils.withMain
import com.github.jing332.common.LogEntry
import com.github.jing332.common.LogLevel
import com.github.jing332.tts_server_android.UserTtsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UserTtsLogViewModel : ViewModel() {
    companion object {
        const val MAX_SIZE = 150
    }

    val logs = mutableStateListOf<LogEntry>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pull()

            UserTtsLogger.register { log ->
                if (logs.size > MAX_SIZE) {
                    logs.removeRange(0, 10)
                }
                logs.add(log)
            }
        }
    }

    fun clear() {
        logs.clear()
        UserTtsLogger.clear()
    }

    fun logDir(): String {
        return UserTtsLogger.file.absolutePath
    }

    private fun toLogEntry(line: String): LogEntry? {
        return runCatching {
            val parts = line.split(" | ", limit = 3)
            LogEntry(
                level = LogLevel.INFO,
                time = parts.getOrNull(0).orEmpty(),
                message = parts.getOrNull(2).orEmpty()
            )
        }.getOrNull()
    }

    private suspend fun pull() {
        runCatching {
            if (!UserTtsLogger.file.exists()) return

            val lines = UserTtsLogger.file.readLines().takeLast(MAX_SIZE)

            withMain {
                lines.forEach { line ->
                    toLogEntry(line)?.let { logs.add(it) }
                }
            }
        }
    }
}