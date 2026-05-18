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
        val levelName = event.level?.toString().orEmpty()

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
                    message.contains("版本确认") ||
                    message.contains("matchDialogFromCache") ||
                    message.contains("writeDialogCache") ||
                    message.contains("姓名分析") ||
                    message.contains("processCharacter") ||
                    message.contains("后置情绪模块")

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

        val isPluginRuntimeLogger =
            loggerName.contains("TtsPlugin", ignoreCase = true) ||
                    loggerName.contains("PluginTts", ignoreCase = true) ||
                    loggerName.contains("PluginTtsProvider", ignoreCase = true) ||
                    loggerName.contains("TtsPluginEngine", ignoreCase = true)

        val isPluginMarkedLog =
            message.contains("[Plugin]", ignoreCase = true) ||
                    message.contains("[插件]", ignoreCase = true)

        val isPluginImportantMessage =
            message.contains("getAudio", ignoreCase = true) ||
                    message.contains("audio", ignoreCase = true) ||
                    message.contains("合成", ignoreCase = true) ||
                    message.contains("请求", ignoreCase = true) ||
                    message.contains("响应", ignoreCase = true) ||
                    message.contains("失败", ignoreCase = true) ||
                    message.contains("异常", ignoreCase = true) ||
                    message.contains("error", ignoreCase = true) ||
                    message.contains("warn", ignoreCase = true)

        val isWarnOrError =
            levelName.equals("WARN", ignoreCase = true) ||
                    levelName.equals("ERROR", ignoreCase = true)

        val isPluginJsConsoleLog =
            loggerName.contains("JS-Console", ignoreCase = true) &&
                    !isSpeechRuleJsLog

        val isPluginNoisyListLog =
            message.startsWith("书籍列表:") ||
                    message.startsWith("原始书籍列表:") ||
                    message.startsWith("清理后书籍列表:") ||
                    message.startsWith("所有角色名称:") ||
                    message.startsWith("添加角色选项:")

        val isPluginLog =
            !isPluginNoisyListLog &&
                    (
                            isPluginMarkedLog ||
                                    isPluginJsConsoleLog ||
                                    (
                                            isPluginRuntimeLogger &&
                                                    (isWarnOrError || isPluginImportantMessage)
                                            )
                            )

        return if (isSystemTtsLog || isSpeechRuleLog || isPluginLog) {
            val normalizedMessage = when {
                message.contains("[SpeechRule]", ignoreCase = true) -> message

                message.contains("[朗读规则]", ignoreCase = true) ->
                    message.replace("[朗读规则]", "[SpeechRule]")

                isSpeechRuleLog ->
                    "[SpeechRule] $message"

                message.contains("[Plugin]", ignoreCase = true) ->
                    message

                message.contains("[插件]", ignoreCase = true) ->
                    message.replace("[插件]", "[Plugin]")

                isPluginLog ->
                    "[Plugin] $message"

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
