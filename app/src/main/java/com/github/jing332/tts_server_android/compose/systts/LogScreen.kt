package com.github.jing332.tts_server_android.compose.systts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.github.jing332.common.LogEntry
import com.github.jing332.common.toArgb
import com.github.jing332.common.toLogLevelChar
import com.github.jing332.common.utils.toast
import com.github.jing332.compose.ComposeExtensions.toAnnotatedString
import com.github.jing332.compose.widgets.ControlBottomBarVisibility
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.LocalBottomBarBehavior
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.FilePickerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private data class DisplayLogEntry(
    val id: String,
    val time: String,
    val levelChar: String,
    val message: String,
    val color: Color,
)

private fun logColorFromChar(
    levelChar: String,
    isDarkTheme: Boolean,
): Color {
    return when (levelChar.uppercase()) {
        "E" -> Color(0xFFE53935)
        "W" -> Color(0xFFFFA000)
        "I" -> Color(0xFF43A047)
        "D" -> if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
        "V" -> if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF757575)
        else -> if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF212121)
    }
}

private fun readFileTailText(
    file: File,
    maxBytes: Int = 2 * 1024 * 1024,
): String {
    return runCatching {
        if (!file.exists() || file.length() <= 0L) {
            ""
        } else {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                val readSize = minOf(fileLength, maxBytes.toLong()).toInt()
                val start = fileLength - readSize

                val buffer = ByteArray(readSize)
                raf.seek(start)
                raf.readFully(buffer)

                String(buffer, Charsets.UTF_8)
            }
        }
    }.getOrDefault("")
}

private fun readHistorySystemTtsLog(
    context: Context,
    isDarkTheme: Boolean,
    keyword: String,
    maxEntries: Int = 3000,
): List<DisplayLogEntry> {
    return runCatching {
        val key = keyword.trim()
        if (key.isBlank()) return emptyList()

        val dir = context.getExternalFilesDir("log") ?: return emptyList()
        val file = File(dir, "system_tts.log")
        if (!file.exists()) return emptyList()

        val text = readFileTailText(file)
        if (text.isBlank()) return emptyList()

        val lineRegex = Regex(
            """^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)\s+([VDIWEA])(?:\s+(.*))?$"""
        )

        val result = mutableListOf<DisplayLogEntry>()

        var currentTime = ""
        var currentLevel = "I"
        val currentMessage = StringBuilder()

        fun flush() {
            val msg = currentMessage.toString().trim()
            if (currentTime.isNotBlank() || msg.isNotBlank()) {
                val safeTime = currentTime.ifBlank { "历史日志" }

                val matched =
                    msg.contains(key, ignoreCase = true) ||
                            safeTime.contains(key, ignoreCase = true) ||
                            currentLevel.contains(key, ignoreCase = true)

                if (matched) {
                    result += DisplayLogEntry(
                        id = "file_${safeTime}_${currentLevel}_${msg.hashCode()}_${result.size}",
                        time = safeTime,
                        levelChar = currentLevel,
                        message = msg,
                        color = logColorFromChar(currentLevel, isDarkTheme)
                    )
                }
            }

            currentTime = ""
            currentLevel = "I"
            currentMessage.clear()
        }

        text.lineSequence().forEach { line ->
            val match = lineRegex.find(line)

            if (match != null) {
                flush()

                currentTime = match.groupValues[1]
                currentLevel = match.groupValues[2]
                val firstMessageLine = match.groupValues.getOrNull(3).orEmpty()

                if (firstMessageLine.isNotBlank()) {
                    currentMessage.append(firstMessageLine)
                }
            } else {
                if (currentMessage.isNotEmpty()) {
                    currentMessage.append('\n')
                }
                currentMessage.append(line)
            }
        }

        flush()

        result.takeLast(maxEntries)
    }.getOrElse {
        emptyList()
    }
}

private fun buildExportLogText(items: List<DisplayLogEntry>): String {
    return items.joinToString(separator = "\n\n") { log ->
        buildString {
            append(log.time)
            append('\t')
            append(log.levelChar)
            append('\n')
            append(log.message)
        }
    }
}

private fun buildLogClipboardText(log: DisplayLogEntry): String {
    return buildString {
        append(log.time)
        append('\t')
        append(log.levelChar)
        append('\n')
        append(log.message)
    }
}

private fun isPluginLog(message: String): Boolean {
    return message.contains("[Plugin]", ignoreCase = true)
}

private fun isSpeechRuleLog(message: String): Boolean {
    return message.contains("[SpeechRule]", ignoreCase = true) ||
            message.contains("[朗读规则]", ignoreCase = true)
}

private fun simplifyVoiceLogMessageForDisplay(message: String): String {
    if (!message.contains("获取成功")) return message

    val voiceIdRegex = Regex(
        pattern = """,\s*(?:zh|en|ja|ko|yue|cmn)_[^,<>\s]*?(?:bigtts|mars_bigtts)[^,<>\s]*\s*,""",
        option = RegexOption.IGNORE_CASE
    )

    return message.replace(voiceIdRegex, ", ")
}
private fun DisplayLogEntry.matchesKeyword(keyword: String): Boolean {
    val key = keyword.trim()
    if (key.isBlank()) return false

    return message.contains(key, ignoreCase = true) ||
            time.contains(key, ignoreCase = true) ||
            levelChar.contains(key, ignoreCase = true)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    modifier: Modifier,
    list: List<LogEntry>,
    listState: LazyListState = rememberLazyListState(),
    onClearLogs: (() -> Unit)? = null,
) {
    ControlBottomBarVisibility(listState, LocalBottomBarBehavior.current)

    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    val logFileSaver =
        rememberLauncherForActivityResult(AppActivityResultContracts.filePickerActivity()) {
        }

    var showHistorySearchDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    var historySearchInput by rememberSaveable { mutableStateOf("") }
    var historyKeyword by rememberSaveable { mutableStateOf("") }
    var showHistoryResult by rememberSaveable { mutableStateOf(false) }
    var isReadingHistory by rememberSaveable { mutableStateOf(false) }

    var filterError by rememberSaveable { mutableStateOf(false) }
    var filterWarn by rememberSaveable { mutableStateOf(false) }
    var filterInfo by rememberSaveable { mutableStateOf(false) }
    var filterDebug by rememberSaveable { mutableStateOf(false) }
    var filterVerbose by rememberSaveable { mutableStateOf(false) }

    var filterPluginLog by rememberSaveable { mutableStateOf(false) }
    var filterSpeechRuleLog by rememberSaveable { mutableStateOf(false) }

    var tempFilterError by rememberSaveable { mutableStateOf(false) }
    var tempFilterWarn by rememberSaveable { mutableStateOf(false) }
    var tempFilterInfo by rememberSaveable { mutableStateOf(false) }
    var tempFilterDebug by rememberSaveable { mutableStateOf(false) }
    var tempFilterVerbose by rememberSaveable { mutableStateOf(false) }

    var tempFilterPluginLog by rememberSaveable { mutableStateOf(false) }
    var tempFilterSpeechRuleLog by rememberSaveable { mutableStateOf(false) }

    var refreshTick by remember { mutableStateOf(0) }
    var autoFollowBottom by rememberSaveable { mutableStateOf(true) }
    var historyFileLogs by remember { mutableStateOf<List<DisplayLogEntry>>(emptyList()) }

    fun copyLogToClipboard(log: DisplayLogEntry) {
        val text = buildLogClipboardText(log)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("tts-server-log", text)
        )
        context.toast("已复制该条日志")
    }

    fun exportLogsByRange(title: String, items: List<DisplayLogEntry>) {
        if (items.isEmpty()) {
            context.toast("没有可导出的日志")
            return
        }

        val text = buildExportLogText(items)
        val safeTitle = title
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
            .ifBlank { "logs" }

        logFileSaver.launch(
            FilePickerActivity.RequestSaveFile(
                fileName = "ttsrv-${safeTitle}-${System.currentTimeMillis()}.txt",
                fileMime = "text/plain",
                fileBytes = text.toByteArray(Charsets.UTF_8)
            )
        )
    }

    fun openFilterDialog() {
        tempFilterError = filterError
        tempFilterWarn = filterWarn
        tempFilterInfo = filterInfo
        tempFilterDebug = filterDebug
        tempFilterVerbose = filterVerbose

        tempFilterPluginLog = filterPluginLog
        tempFilterSpeechRuleLog = filterSpeechRuleLog

        showFilterDialog = true
    }

    if (showHistorySearchDialog) {
        AlertDialog(
            onDismissRequest = {
                showHistorySearchDialog = false
            },
            title = {
                Text("搜索日志")
            },
            text = {
                OutlinedTextField(
                    value = historySearchInput,
                    onValueChange = {
                        historySearchInput = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text("输入关键词")
                    },
                    leadingIcon = {
                        Text("🔍")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = historySearchInput.trim().isNotBlank(),
                    onClick = {
                        val key = historySearchInput.trim()

                        showHistorySearchDialog = false
                        isReadingHistory = true

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                readHistorySystemTtsLog(
                                    context = context,
                                    isDarkTheme = darkTheme,
                                    keyword = key
                                )
                            }

                            historyKeyword = key
                            historyFileLogs = result
                            showHistoryResult = true
                            isReadingHistory = false
                            autoFollowBottom = false

                            kotlin.runCatching {
                                listState.requestScrollToItem(0)
                            }
                        }
                    }
                ) {
                    Text("搜索")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showHistorySearchDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = {
                showFilterDialog = false
            },
            title = {
                Text("筛选日志级别")
            },
            text = {
                Column {
                    Text(
                        text = "选择要显示的日志级别",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = tempFilterError,
                            onClick = { tempFilterError = !tempFilterError },
                            label = { Text("ERROR") }
                        )

                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = tempFilterWarn,
                            onClick = { tempFilterWarn = !tempFilterWarn },
                            label = { Text("WARN") }
                        )

                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = tempFilterInfo,
                            onClick = { tempFilterInfo = !tempFilterInfo },
                            label = { Text("INFO") }
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = tempFilterDebug,
                            onClick = { tempFilterDebug = !tempFilterDebug },
                            label = { Text("DEBUG") }
                        )

                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = tempFilterVerbose,
                            onClick = { tempFilterVerbose = !tempFilterVerbose },
                            label = { Text("VERBOSE") }
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 20.dp),
                        thickness = 0.5.dp
                    )

                    Text(
                        text = "调试选项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = tempFilterPluginLog,
                            onClick = { tempFilterPluginLog = !tempFilterPluginLog },
                            label = { Text("插件日志") }
                        )

                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = tempFilterSpeechRuleLog,
                            onClick = { tempFilterSpeechRuleLog = !tempFilterSpeechRuleLog },
                            label = { Text("朗读规则日志") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        filterError = tempFilterError
                        filterWarn = tempFilterWarn
                        filterInfo = tempFilterInfo
                        filterDebug = tempFilterDebug
                        filterVerbose = tempFilterVerbose

                        filterPluginLog = tempFilterPluginLog
                        filterSpeechRuleLog = tempFilterSpeechRuleLog

                        showFilterDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFilterDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            refreshTick++
        }
    }

    val runtimeLogs = remember(list, darkTheme, refreshTick) {
        list.mapIndexed { index, log ->
            DisplayLogEntry(
                id = "runtime_${log.time}_${log.level}_${log.message.hashCode()}_$index",
                time = log.time,
                levelChar = log.level.toLogLevelChar(),
                message = log.message,
                color = Color(log.level.toArgb(isDarkTheme = darkTheme))
            )
        }
    }

    val sourceLogs = remember(showHistoryResult, runtimeLogs, historyFileLogs, historyKeyword) {
        if (showHistoryResult) {
            val currentMatches = runtimeLogs.filter {
                it.matchesKeyword(historyKeyword)
            }

            // 搜索结果 = 当前页面实时日志 + 文件日志
            // distinctBy 防止当前日志已经写入 system_tts.log 后重复显示
            (currentMatches + historyFileLogs).distinctBy {
                "${it.time}\u0000${it.levelChar}\u0000${it.message}"
            }
        } else {
            runtimeLogs
        }
    }

    val filteredList = remember(
        sourceLogs,
        filterError,
        filterWarn,
        filterInfo,
        filterDebug,
        filterVerbose,
        filterPluginLog,
        filterSpeechRuleLog,
        refreshTick
    ) {
        val selectedLevels = buildSet {
            if (filterError) add("E")
            if (filterWarn) add("W")
            if (filterInfo) add("I")
            if (filterDebug) add("D")
            if (filterVerbose) add("V")
        }

        val levelFilterEnabled = selectedLevels.isNotEmpty()

        sourceLogs.filter { log ->
            val msg = log.message

            val levelMatched =
                !levelFilterEnabled || selectedLevels.contains(log.levelChar.uppercase())

            val plugin = isPluginLog(msg)
            val speechRule = isSpeechRuleLog(msg)
            val normal = !plugin && !speechRule

            val debugMatched =
                normal ||
                        (filterPluginLog && plugin) ||
                        (filterSpeechRuleLog && speechRule)

            levelMatched && debugMatched
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
            },
            title = {
                Text("导出日志")
            },
            text = {
                Column {
                    Text(
                        text = "导出范围基于当前页面显示结果：${filteredList.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        enabled = filteredList.isNotEmpty(),
                        onClick = {
                            showExportDialog = false
                            exportLogsByRange(
                                title = "current_all",
                                items = filteredList
                            )
                        }
                    ) {
                        Text("导出当前显示全部：${filteredList.size} 条")
                    }

                    TextButton(
                        enabled = filteredList.isNotEmpty(),
                        onClick = {
                            showExportDialog = false
                            exportLogsByRange(
                                title = "last_100",
                                items = filteredList.takeLast(100)
                            )
                        }
                    ) {
                        Text("导出最近 100 条")
                    }

                    TextButton(
                        enabled = filteredList.isNotEmpty(),
                        onClick = {
                            showExportDialog = false
                            exportLogsByRange(
                                title = "last_500",
                                items = filteredList.takeLast(500)
                            )
                        }
                    ) {
                        Text("导出最近 500 条")
                    }

                    TextButton(
                        enabled = filteredList.isNotEmpty(),
                        onClick = {
                            showExportDialog = false
                            exportLogsByRange(
                                title = "last_1000",
                                items = filteredList.takeLast(1000)
                            )
                        }
                    ) {
                        Text("导出最近 1000 条")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = {
                showClearDialog = false
            },
            title = {
                Text("清空日志")
            },
            text = {
                Text("确定要清空当前日志吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false

                        if (showHistoryResult) {
                            historyFileLogs = emptyList()
                            context.toast("已清空当前历史结果")
                        } else {
                            if (onClearLogs != null) {
                                onClearLogs()
                            } else {
                                context.toast("当前页面未接入清空日志")
                            }
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    val lastLogKey = filteredList.lastOrNull()?.let {
        "${it.time}_${it.levelChar}_${it.message.hashCode()}_${filteredList.size}"
    } ?: ""

    Box(modifier) {
        val isAtBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val visibleItemsInfo = layoutInfo.visibleItemsInfo

                if (layoutInfo.totalItemsCount <= 0 || visibleItemsInfo.isEmpty()) {
                    true
                } else {
                    val lastVisibleItem = visibleItemsInfo.last()
                    lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
                }
            }
        }

        LaunchedEffect(isAtBottom) {
            if (isAtBottom) {
                autoFollowBottom = true
            } else if (listState.isScrollInProgress) {
                autoFollowBottom = false
            }
        }

        LaunchedEffect(
            lastLogKey,
            showHistoryResult,
            filterError,
            filterWarn,
            filterInfo,
            filterDebug,
            filterVerbose,
            filterPluginLog,
            filterSpeechRuleLog
        ) {
            if (filteredList.isNotEmpty() && autoFollowBottom && !showHistoryResult) {
                delay(80)
                kotlin.runCatching {
                    listState.scrollToItem(filteredList.size)
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(start = 24.dp, end = 8.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    enabled = !isReadingHistory,
                    onClick = {
                        historySearchInput = ""
                        showHistorySearchDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索日志"
                    )
                }

                IconButton(
                    onClick = {
                        openFilterDialog()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "筛选日志"
                    )
                }

                IconButton(
                    enabled = filteredList.isNotEmpty(),
                    onClick = {
                        showExportDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "导出日志"
                    )
                }

                IconButton(
                    onClick = {
                        showClearDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空日志"
                    )
                }
            }

            if (showHistoryResult) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "搜索：$historyKeyword，${filteredList.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            showHistoryResult = false
                            historyKeyword = ""
                            historySearchInput = ""
                            historyFileLogs = emptyList()
                            autoFollowBottom = true

                            kotlin.runCatching {
                                listState.requestScrollToItem(filteredList.size)
                            }
                        }
                    ) {
                        Text("返回当前")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (filteredList.isEmpty()) {
                    Box(Modifier.align(Alignment.Center)) {
                        Text(
                            text = if (showHistoryResult) {
                                "没有匹配的日志"
                            } else {
                                stringResource(R.string.empty_list)
                            },
                            style = MaterialTheme.typography.displaySmall
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    itemsIndexed(
                        filteredList,
                        key = { index, log ->
                            "${log.id}_$index"
                        }
                    ) { index, log ->
                        val style = MaterialTheme.typography.bodyMedium
                        val displayMessage = remember(log.message) {
                            simplifyVoiceLogMessageForDisplay(log.message)
                        }

                        val spanned = remember(displayMessage) {
                            HtmlCompat.fromHtml(
                                displayMessage,
                                HtmlCompat.FROM_HTML_MODE_COMPACT
                            ).toAnnotatedString()
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copyLogToClipboard(log)
                                }
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = log.time,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    text = "\t${log.levelChar}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(
                                text = spanned,
                                color = log.color,
                                style = style,
                                lineHeight = style.lineHeight * 0.75f,
                            )

                            if (index < filteredList.size - 1) {
                                HorizontalDivider(thickness = 0.3.dp)
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(48.dp),
            visible = !isAtBottom && !showHistoryResult,
            enter = fadeIn() + expandIn(expandFrom = Alignment.BottomCenter),
            exit = shrinkOut(shrinkTowards = Alignment.BottomCenter) + fadeOut(),
        ) {
            FloatingActionButton(
                modifier = Modifier.padding(8.dp),
                shape = CircleShape,
                onClick = {
                    if (filteredList.isNotEmpty()) {
                        autoFollowBottom = true
                        kotlin.runCatching {
                            listState.requestScrollToItem(filteredList.size)
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowDown,
                    stringResource(id = R.string.move_to_bottom)
                )
            }
        }
    }
}