package com.github.jing332.tts_server_android

import androidx.annotation.Keep
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import cn.hutool.core.date.LocalDateTimeUtil
import com.github.jing332.common.LogEntry
import com.github.jing332.common.toLogLevel
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import java.time.format.DateTimeFormatter
import java.util.TimeZone

@Keep
class SysttsFilter : Filter<ILoggingEvent>() {
    companion object {
        const val TAG = "SysttsFilter"
        const val ACTION_ON_LOG = "SystemFilter.SYSTTS_ON_LOG"
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }

    override fun decide(event: ILoggingEvent): FilterReply {
        val loggerName = event.loggerName.orEmpty()
        val message = event.message.orEmpty()

        val isSystemTtsLog = loggerName == SystemTtsService.TAG

        val isSpeechRuleJsLog =
            message.startsWith("【") ||
                    message.contains("运行时情绪") ||
                    message.contains("规则情绪桥接") ||
                    message.contains("情绪调试") ||
                    message.contains("TTS 前最终标签") ||
                    message.contains("handleText强制触发getTagName") ||
                    message.contains("自适应模式") ||
                    message.contains("单模型直判") ||
                    message.contains("版本确认")

        val isSpeechRuleLog =
            message.contains("[SpeechRule]", ignoreCase = true) ||
                    message.contains("[朗读规则]", ignoreCase = true) ||
                    (
                            loggerName.contains("TextProcessor", ignoreCase = true) &&
                                    message.contains("朗读规则")
                            ) ||
                    (
                            loggerName.contains("JS-Console", ignoreCase = true) &&
                                    isSpeechRuleJsLog
                            )

        val isPluginLog =
            message.contains("[Plugin]", ignoreCase = true) ||
                    loggerName.contains("Plugin", ignoreCase = true) ||
                    loggerName.contains("TtsPlugin", ignoreCase = true)

        return if (isSystemTtsLog || isSpeechRuleLog || isPluginLog) {
            val normalizedMessage = when {
                message.contains("[SpeechRule]", ignoreCase = true) -> message
                message.contains("[朗读规则]", ignoreCase = true) -> message.replace("[朗读规则]", "[SpeechRule]")
                message.contains("[Plugin]", ignoreCase = true) -> message
                isSpeechRuleLog -> "[SpeechRule] $message"
                isPluginLog -> "[Plugin] $message"
                else -> message
            }

            SysttsLogger.log(
                LogEntry(
                    level = event.level.toString().toLogLevel(),
                    time = LocalDateTimeUtil.of(event.timeStamp, TimeZone.getDefault())
                        .format(dateFormatter),
                    message = normalizedMessage
                )
            )

            FilterReply.ACCEPT
        } else {
            FilterReply.DENY
        }
    }
}