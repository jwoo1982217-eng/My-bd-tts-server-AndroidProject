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

        return if (event.loggerName == SystemTtsService.TAG) {
            SysttsLogger.log(
                LogEntry(
                    level = event.level.toString().toLogLevel(),
                    time = LocalDateTimeUtil.of(event.timeStamp, TimeZone.getDefault())
                        .format(dateFormatter),
                    message = event.message
                )
            )

            FilterReply.ACCEPT
        } else FilterReply.DENY
    }
}