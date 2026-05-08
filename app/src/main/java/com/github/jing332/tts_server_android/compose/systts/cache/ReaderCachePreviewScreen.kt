package com.github.jing332.tts_server_android.compose.systts.cache

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.common.utils.toast
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.nav.NavTopAppBar
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import com.github.jing332.tts_server_android.service.systts.help.AudioCacheFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderCachePreviewScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    var books by remember { mutableStateOf<List<AudioCacheFactory.PreviewBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var clearAllDialog by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            books = withContext(Dispatchers.IO) {
                AudioCacheFactory.listPreview(context)
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    if (clearAllDialog) {
        AlertDialog(
            onDismissRequest = { clearAllDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearAllDialog = false
                        scope.launch(Dispatchers.IO) {
                            AudioCacheFactory.clearAll(context)
                            withContext(Dispatchers.Main) {
                                context.toast("缓存已清理")
                                reload()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text("清理全部缓存") },
            text = { Text("会删除所有书的台词本队列和本地 PCM 音频缓存。") }
        )
    }

    val scrollBehaviour = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.nestedScroll(scrollBehaviour.nestedScrollConnection),
        topBar = {
            NavTopAppBar(
                title = { Text(stringResource(R.string.script_preview)) },
                scrollBehavior = scrollBehaviour,
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        enabled = books.isNotEmpty(),
                        onClick = { clearAllDialog = true }
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "清理缓存")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
        ) {
            if (loading) {
                item {
                    Text(
                        text = stringResource(R.string.loading),
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (books.isEmpty()) {
                item {
                    Text(
                        text = "还没有本地缓存。打开阅读朗读后，TTS 会按预加载窗口生成台词本队列。",
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            books.forEach { book ->
                item(key = "${book.bookKey}_title") {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            text = book.bookName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${book.chapters.size} 章 · ${book.sizeBytes.sizeToReadable()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(book.chapters, key = { it.chapterKey }) { chapter ->
                    ChapterCard(
                        chapter = chapter,
                        expanded = expanded[chapter.chapterKey] == true,
                        onToggle = {
                            expanded[chapter.chapterKey] = expanded[chapter.chapterKey] != true
                        },
                        onClear = {
                            scope.launch(Dispatchers.IO) {
                                AudioCacheFactory.clearChapter(
                                    context,
                                    chapter.bookKey,
                                    chapter.chapterKey
                                )
                                withContext(Dispatchers.Main) {
                                    context.toast("已清空本章缓存")
                                    reload()
                                }
                            }
                        },
                        onRetry = {
                            context.toast("开始重试失败句子")
                            SystemTtsService.retryReaderAudioCacheFailed(
                                context = context,
                                bookKey = chapter.bookKey,
                                chapterKey = chapter.chapterKey
                            ) { ok ->
                                scope.launch {
                                    context.toast(if (ok) "重试完成" else "TTS 服务未运行，先朗读一次后再试")
                                    reload()
                                }
                            }
                        },
                        onRetryItem = { item ->
                            context.toast("开始重试第 ${item.index} 句")
                            SystemTtsService.retryReaderAudioCacheItem(
                                context = context,
                                bookKey = chapter.bookKey,
                                chapterKey = chapter.chapterKey,
                                itemIndex = item.index
                            ) { ok ->
                                scope.launch {
                                    context.toast(if (ok) "单句重试完成" else "TTS 服务未运行，先朗读一次后再试")
                                    reload()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: AudioCacheFactory.PreviewChapter,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onRetryItem: (AudioCacheFactory.PreviewItem) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开"
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${chapter.chapterIndex}. ${chapter.title.ifBlank { "未命名章节" }}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "ready ${chapter.readyCount}/${chapter.items.size} · failed ${chapter.failedCount} · ${chapter.sizeBytes.sizeToReadable()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(chapter.status)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = chapter.failedCount > 0,
                    onClick = onRetry
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重试失败")
                }
                OutlinedButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("清空本章")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chapter.items.forEach { item ->
                        QueueItemRow(
                            item = item,
                            onRetry = { onRetryItem(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: AudioCacheFactory.PreviewItem,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.index.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = item.tag.ifBlank { "旁白" },
                modifier = Modifier.weight(0.8f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.voice.ifBlank { "默认音色" },
                modifier = Modifier.weight(0.9f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StatusText(item.status)
            if (item.status == "failed" || item.error.isNotBlank()) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "重试这一句")
                }
            }
        }

        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        if (item.error.isNotBlank()) {
            Text(
                text = item.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    AssistChip(
        onClick = {},
        label = { Text(status.ifBlank { "pending" }) }
    )
}

@Composable
private fun StatusText(status: String) {
    val color = when (status) {
        "ready" -> Color(0xFF2E7D32)
        "failed" -> MaterialTheme.colorScheme.error
        "caching_audio", "analyzing", "queue_ready" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.ifBlank { "pending" },
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
