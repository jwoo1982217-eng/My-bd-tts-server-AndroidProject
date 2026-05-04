package com.github.jing332.tts_server_android

import com.github.jing332.common.LogEntry
import com.github.jing332.common.LogLevel
import com.github.jing332.tts_server_android.constant.AppConst
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UserTtsLogger {
    private const val MAX_CALLBACKS = 8

    private val callbacks = mutableListOf<(LogEntry) -> Unit>()

    val file: File = File(
        AppConst.externalFilesDir.absolutePath +
                File.separator +
                "log" +
                File.separator +
                "user_tts.log"
    )

    fun register(callback: (LogEntry) -> Unit) {
        if (callbacks.size > MAX_CALLBACKS) callbacks.clear()
        callbacks.add(callback)
    }

    fun logSpeak(
        text: String,
        voiceName: String,
    ) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val safeText = text
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()

        val safeVoice = voiceName.ifBlank { "未知音色" }

        val message = "音色：$safeVoice<br>文本：$safeText"

        val entry = LogEntry(
            level = LogLevel.INFO,
            time = time,
            message = message
        )

        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()

            FileWriter(file, true).use {
                it.append("$time | INFO | $message\n")
            }
        }

        callbacks.forEach {
            runCatching { it(entry) }
        }
    }

    fun logSpeechRule(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val safeMessage = message
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()

        val finalMessage = "🧩朗读规则：$safeMessage"

        val entry = LogEntry(
            level = LogLevel.INFO,
            time = time,
            message = finalMessage
        )

        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()

            FileWriter(file, true).use {
                it.append("$time | INFO | $finalMessage\n")
            }
        }

        callbacks.forEach {
            runCatching { it(entry) }
        }
    }


    fun clear() {
        runCatching {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            FileWriter(file, false).use { it.write("") }
        }
    }
}