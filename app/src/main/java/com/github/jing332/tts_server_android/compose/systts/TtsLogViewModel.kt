package com.github.jing332.tts_server_android.compose.systts

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.utils.withMain
import com.github.jing332.common.LogEntry
import com.github.jing332.common.LogLevel
import com.github.jing332.common.toLogLevel
import com.github.jing332.common.utils.runOnUI
import com.github.jing332.tts_server_android.SysttsLogger
import com.github.jing332.tts_server_android.constant.AppConst
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter

class TtsLogViewModel : ViewModel() {
    companion object {
        const val TAG = "TtsLogViewModel"

        const val MAX_SIZE = 150

        // 日志文件超过 2MB 自动裁剪
        const val MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L

        // 裁剪时保留最后 3000 行
        const val KEEP_LOG_LINES = 3000

        private val logger = KotlinLogging.logger(TAG)

        val file =
            File(AppConst.externalFilesDir.absolutePath + File.separator + "log" + File.separator + "system_tts.log")
    }

    val logs = mutableStateListOf<LogEntry>()

    private var readLineCount = 0

    private val twoLineHeaderRegex =
        Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWE])$""")

    private val logbackRegex =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[.*?]\s+([A-Z]+)\s+(.+?)\s+-\s+(.*)$""")

    fun clear() {
        logs.clear()
        readLineCount = 0

        runCatching {
            FileWriter(file, false).use { it.write(CharArray(0)) }
        }.onFailure {
            addLogEntry(LogEntry(level = LogLevel.ERROR, message = it.stackTraceToString()))
        }
    }

    private fun parseLevel(level: String): Int {
        val normalized = when (level.trim().uppercase()) {
            "TRACE" -> "V"
            "VERBOSE" -> "V"
            "DEBUG" -> "D"
            "INFO" -> "I"
            "WARN" -> "W"
            "WARNING" -> "W"
            "ERROR" -> "E"
            else -> level.trim()
        }

        return runCatching {
            normalized.toLogLevel()
        }.getOrDefault("I".toLogLevel())
    }
    private fun normalizeMessage(loggerName: String, message: String): String {
        val msg = message.trim()

        if (
            msg.contains("[Plugin]", ignoreCase = true) ||
            msg.contains("[SpeechRule]", ignoreCase = true) ||
            msg.contains("[朗读规则]", ignoreCase = true)
        ) {
            return msg.replace("[朗读规则]", "[SpeechRule]")
        }

        if (
            loggerName.contains("TextProcessor", ignoreCase = true) &&
            msg.contains("朗读规则")
        ) {
            return "[SpeechRule] $msg"
        }

        if (loggerName.contains("JS-Console", ignoreCase = true)) {
            val isSpeechRule =
                msg.startsWith("【") ||
                        msg.contains("运行时情绪") ||
                        msg.contains("规则情绪桥接") ||
                        msg.contains("情绪调试") ||
                        msg.contains("TTS前最终标签") ||
                        msg.contains("handleText强制触发getTagName") ||
                        msg.contains("自适应模式") ||
                        msg.contains("单模型直判") ||
                        msg.contains("版本确认")

            return if (isSpeechRule) {
                "[SpeechRule] $msg"
            } else {
                "[Plugin] $msg"
            }
        }

        return msg
    }

    private fun parseSingleLine(line: String): LogEntry? {
        // 兼容老格式：time | level | message
        val oldParts = line.split(" | ", limit = 3)
        if (oldParts.size == 3) {
            return LogEntry(
                level = parseLevel(oldParts[1]),
                time = oldParts[0],
                message = normalizeMessage("", oldParts[2])
            )
        }

        // 兼容 logback 格式：
        // 04-25 05:34:27.045 [worker] INFO JS-Console - message
        val logbackMatch = logbackRegex.find(line)
        if (logbackMatch != null) {
            val time = logbackMatch.groupValues[1]
            val level = logbackMatch.groupValues[2]
            val loggerName = logbackMatch.groupValues[3]
            val message = logbackMatch.groupValues[4]

            return LogEntry(
                level = parseLevel(level),
                time = time,
                message = normalizeMessage(loggerName, message)
            )
        }

        return null
    }

    private fun parseLines(lines: List<String>): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            val single = parseSingleLine(line)
            if (single != null) {
                result.add(single)
                index++
                continue
            }

            // 兼容当前 App 普通日志格式：
            // 2026-04-25 11:40:45.499 I
            // 请求音频：xxx
            val header = twoLineHeaderRegex.find(line)
            if (header != null && index + 1 < lines.size) {
                val time = header.groupValues[1]
                val level = header.groupValues[2]
                val message = lines[index + 1]

                result.add(
                    LogEntry(
                        level = parseLevel(level),
                        time = time,
                        message = normalizeMessage("", message)
                    )
                )

                index += 2
                continue
            }

            index++
        }

        return result
    }

    private fun addLogEntry(logEntry: LogEntry) {
        if (logs.size > MAX_SIZE) {
            logs.removeRange(0, minOf(10, logs.size))
        }

        logs.add(logEntry)
    }

    private fun isSpecialLog(logEntry: LogEntry): Boolean {
        val msg = logEntry.message

        return msg.contains("[Plugin]", ignoreCase = true) ||
                msg.contains("[SpeechRule]", ignoreCase = true) ||
                msg.contains("[朗读规则]", ignoreCase = true)
    }

    private fun trimLogFileIfNeeded() {
        runCatching {
            if (!file.exists()) return
            if (file.length() <= MAX_LOG_FILE_SIZE) return

            val keepLines = file.readLines()
                .takeLast(KEEP_LOG_LINES)

            FileWriter(file, false).use { writer ->
                keepLines.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
            }

            readLineCount = keepLines.size
        }.onFailure {
            logger.error(it) { "trim system_tts.log failed" }
        }
    }

    fun logDir(): String {
        return ""
    }

    init {
        try {
            viewModelScope.launch(Dispatchers.IO) {
                pull()

                SysttsLogger.register { log ->
                    runOnUI {
                        addLogEntry(log)
                    }
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                watchLogFile()
            }
        } catch (e: Exception) {
            logger.error(e) { "init log view model failed" }
        }
    }

    fun add(line: String) {
        runCatching {
            parseLines(listOf(line)).forEach {
                addLogEntry(it)
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun pull() {
        runCatching {
            trimLogFileIfNeeded()

            val lines = file.readLines()
            readLineCount = lines.size

            val entries = parseLines(lines.takeLast(MAX_SIZE * 8))
                .takeLast(MAX_SIZE)

            withMain {
                entries.forEach {
                    addLogEntry(it)
                }
            }
        }.onFailure {
            addLogEntry(LogEntry(level = LogLevel.ERROR, message = it.stackTraceToString()))
        }
    }

    private suspend fun watchLogFile() {
        while (viewModelScope.isActive) {
            delay(500)

            runCatching {
                if (!file.exists()) return@runCatching

                trimLogFileIfNeeded()

                val lines = file.readLines()

                if (lines.size < readLineCount) {
                    readLineCount = 0
                }

                if (lines.size <= readLineCount) {
                    return@runCatching
                }

                val newLines = lines.drop(readLineCount)
                readLineCount = lines.size

                val entries = parseLines(newLines)
                    .filter { isSpecialLog(it) }

                if (entries.isNotEmpty()) {
                    withMain {
                        entries.forEach {
                            addLogEntry(it)
                        }
                    }
                }
            }.onFailure {
                logger.error(it) { "watch system_tts.log failed" }
            }
        }
    }
}