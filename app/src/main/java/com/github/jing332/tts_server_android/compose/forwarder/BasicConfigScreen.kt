package com.github.jing332.tts_server_android.compose.forwarder

import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.jing332.common.LogEntry
import com.github.jing332.common.LogLevel
import com.github.jing332.compose.widgets.DenseOutlinedField
import com.github.jing332.compose.widgets.LocalBroadcastReceiver
import com.github.jing332.compose.widgets.SwitchFloatingButton
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.constant.KeyConst
import com.github.jing332.tts_server_android.service.forwarder.ForwarderLogHistory

@Suppress("DEPRECATION")
@Composable
internal fun BasicConfigScreen(
    modifier: Modifier,
    vm: ConfigViewModel,
    intentFilter: IntentFilter,
    actionOnLog: String,
    actionOnClosed: String,
    actionOnStarted: String,
    isRunning: Boolean,
    onRunningChange: (Boolean) -> Unit,
    switch: () -> Unit,
    port: Int,
    onPortChange: (Int) -> Unit,
) {
    LaunchedEffect(Unit) {
        val history = ForwarderLogHistory.snapshot()
        if (history.isNotEmpty()) {
            vm.loadHistory(history)
        } else if (isRunning) {
            vm.addLog(LogEntry(level = LogLevel.INFO, message = "服务已启动"))
        }
    }

    LaunchedEffect(vm.logs.size) {
        if (vm.logs.isNotEmpty()) {
            vm.logState.animateScrollToItem(vm.logs.lastIndex)
        }
    }

    LocalBroadcastReceiver(intentFilter = intentFilter) { intent ->
        if (intent == null) return@LocalBroadcastReceiver

        when (intent.action) {
            actionOnLog -> {
                intent.getParcelableExtra<LogEntry>(KeyConst.KEY_DATA)?.let { log ->
                    vm.addLog(log)
                }
            }

            actionOnClosed -> {
                onRunningChange(false)
                vm.addLog(LogEntry(level = LogLevel.INFO, message = "服务已关闭"))
            }

            actionOnStarted -> {
                onRunningChange(true)
                vm.addLog(LogEntry(level = LogLevel.INFO, message = "服务已启动"))
            }
        }
    }

    Column(modifier) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            state = vm.logState
        ) {
            items(vm.logs) { log ->
                ForwarderLogItem(log)
                HorizontalDivider()
            }
        }

        Row(Modifier.align(Alignment.CenterHorizontally)) {
            DenseOutlinedField(
                label = { Text(stringResource(R.string.listen_port)) },
                modifier = Modifier.align(Alignment.CenterVertically),
                value = port.toString(),
                onValueChange = {
                    kotlin.runCatching {
                        onPortChange(it.toInt())
                    }
                }
            )

            SwitchFloatingButton(
                modifier = Modifier.padding(8.dp),
                switch = isRunning,
                onSwitchChange = { switch() }
            )
        }
    }
}

@Composable
private fun ForwarderLogItem(log: LogEntry) {
    val levelText = when (log.level) {
        LogLevel.INFO -> "I"
        LogLevel.ERROR -> "E"
        else -> "D"
    }

    val color = when (log.level) {
        LogLevel.INFO -> Color(0xFF4CAF50)
        LogLevel.ERROR -> Color(0xFFE53935)
        else -> Color(0xFF2196F3)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = levelText, color = Color(0xFF333333))
        Text(text = log.message, color = color)
    }
}
