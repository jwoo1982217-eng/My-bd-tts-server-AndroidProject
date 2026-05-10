package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import android.os.Environment
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.MixSynthesizer
import com.github.jing332.tts.SynthesizerConfig
import com.github.jing332.tts.SynthesizerContext
import com.github.jing332.tts.synthesizer.RequestPayload
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleEngine
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AudioCacheFactory {
    private val logger = KotlinLogging.logger("AudioCacheFactory")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warming = AtomicBoolean(false)
    private val cacheWorkMutex = Mutex()

    @Volatile
    private var warmJob: Job? = null

    @Volatile
    private var backgroundManager: MixSynthesizer? = null

    data class CachedAudio(
        val sampleRate: Int,
        val bytes: ByteArray,
        val chapterKey: String,
        val index: Int,
        val voice: String,
    )

    data class PreviewBook(
        val bookKey: String,
        val bookName: String,
        val chapters: List<PreviewChapter>,
        val sizeBytes: Long,
    )

    data class PreviewChapter(
        val bookKey: String,
        val chapterKey: String,
        val chapterIndex: Int,
        val title: String,
        val status: String,
        val items: List<PreviewItem>,
        val readyCount: Int,
        val failedCount: Int,
        val sizeBytes: Long,
        val updatedAt: Long,
        val mp3Status: String,
        val mp3Path: String,
        val mp3SizeBytes: Long,
        val mp3Error: String,
    )

    data class PreviewItem(
        val index: Int,
        val text: String,
        val tag: String,
        val voice: String,
        val status: String,
        val emotion: String,
        val speed: Float,
        val pitch: Float,
        val volume: Float,
        val error: String,
    )

    data class PreviewLog(
        val time: String,
        val source: String,
        val message: String,
    )

    data class AudiobookChapterInput(
        val chapterIndex: Int,
        val chapterTitle: String,
        val chapterText: String,
    )

    data class AudiobookGenerationProgress(
        val status: String,
        val message: String,
        val totalChapters: Int,
        val readyChapters: Int,
        val failedChapters: Int,
        val totalItems: Int,
        val readyItems: Int,
        val failedItems: Int,
    )

    private data class QueueItem(
        val index: Int,
        val text: String,
        val tag: String,
        val voice: String,
        val emotion: String,
        val speed: Float,
        val pitch: Float,
        val volume: Float,
        val raw: JSONObject,
    )

    private data class LegacyQueueSource(
        val text: String,
        val tag: String,
        val voice: String,
        val configId: Long,
    )

    private data class SynthesizedAudio(
        val sampleRate: Int,
        val pcmBytes: ByteArray,
        val sourceBytes: ByteArray?,
        val sourceFormat: String,
    )

    fun getCachedAudio(context: Context, text: String): CachedAudio? {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return null

        val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
        if (!window.optBoolean("ok", false)) return null

        val bookKey = window.optString("bookUrl", window.optString("bookName", "")).md5()
        val chapters = window.optJSONArray("chapters") ?: return null
        val textHash = cleanText.md5()
        val lookupTextHash = cleanText.lookupTextKey().md5()
        val configFingerprint = currentConfigFingerprint()

        for (i in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(i) ?: continue
            val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
            val manifest = readManifest(chapterDir(context, bookKey, chapterKey)) ?: continue
            val items = manifest.optJSONArray("items") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val cachedLookupTextHash = item.optString("lookupTextHash", "").ifBlank {
                    item.optString("text", "").lookupTextKey().md5()
                }
                if (item.optString("textHash") != textHash && cachedLookupTextHash != lookupTextHash) continue
                if (item.optString("configFingerprint") != configFingerprint) continue

                val file = File(item.optString("path", ""))
                if (!file.exists() || file.length() <= 0) continue

                return CachedAudio(
                    sampleRate = item.optInt("sampleRate", 16000).coerceAtLeast(8000),
                    bytes = file.readBytes(),
                    chapterKey = chapterKey,
                    index = item.optInt("index", -1),
                    voice = item.optString("voice", "").ifBlank { item.optString("tag", "") },
                )
            }
        }

        return null
    }

    fun savePlaybackAudio(
        context: Context,
        text: String,
        sampleRate: Int,
        bytes: ByteArray,
    ) {
        if (text.isBlank() || bytes.isEmpty()) return

        scope.launch {
            runCatching {
                val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
                if (!window.optBoolean("ok", false)) return@runCatching

                val bookKey = window.optString("bookUrl", window.optString("bookName", "")).md5()
                val chapter = locateChapterForText(window, text) ?: return@runCatching
                val sentences = splitSentences(chapter.optString("text", ""))
                val index = sentences.indexOfFirst { it.trim() == text.trim() }.takeIf { it >= 0 }
                    ?: nextIndexForPlayback(context, bookKey, chapter)

                saveItem(
                    context = context,
                    bookKey = bookKey,
                    bookName = window.optString("bookName", ""),
                    chapter = chapter,
                    index = index,
                    text = text.trim(),
                    sampleRate = sampleRate,
                    bytes = bytes,
                    status = "partial"
                )
            }.onFailure {
                logger.warn(it) { "save playback cache failed" }
            }
        }
    }

    fun warmCurrentWindow(context: Context, liveManager: MixSynthesizer?) {
        if (liveManager == null) return
        if (!warming.compareAndSet(false, true)) return

        warmJob = scope.launch {
            try {
                cacheWorkMutex.withLock {
                    val manager = getBackgroundManager(context, liveManager)
                    val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
                    if (!window.optBoolean("ok", false)) {
                        appendPreviewLog(
                            context = context,
                            source = "缓存队列",
                            message = "窗口缓存未启动｜阅读端没有返回当前章窗口：${window.optString("error", "unknown")}"
                        )
                        return@withLock
                    }

                    val bookKey = window.optString("bookUrl", window.optString("bookName", "")).md5()
                    val bookName = window.optString("bookName", "")
                    val chapters = window.optJSONArray("chapters") ?: return@withLock

                    appendPreviewLog(
                        context = context,
                        source = "缓存队列",
                        message = "开始窗口缓存｜书=${bookName.ifBlank { bookKey }}｜章节=${chapters.length()}｜按章顺序执行"
                    )

                    for (i in 0 until chapters.length()) {
                        val chapter = chapters.optJSONObject(i) ?: continue
                        if (!chapter.optBoolean("ok", false)) continue

                        val chapterIndex = chapter.optInt("chapterIndex", -1)
                        val chapterTitle = chapter.optString("chapterTitle", "")
                        appendPreviewLog(
                            context = context,
                            source = "缓存队列",
                            message = "开始章节｜$chapterIndex $chapterTitle"
                        )

                        cacheChapter(
                            context = context,
                            manager = manager,
                            bookKey = bookKey,
                            bookName = bookName,
                            chapter = chapter
                        )

                        val status = readManifest(
                            chapterDir(context, bookKey, chapterKey(bookKey, chapterIndex))
                        )?.optString("status", "").orEmpty()
                        appendPreviewLog(
                            context = context,
                            source = "缓存队列",
                            message = "完成章节｜$chapterIndex $chapterTitle｜$status"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "warm current window failed" }
            } finally {
                warming.set(false)
            }
        }
    }

    fun listPreview(context: Context): List<PreviewBook> {
        val root = cacheRoot(context)
        if (!root.exists()) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { bookDir ->
                val chapters = bookDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { chapterDir ->
                        previewChapter(bookDir.name, chapterDir)
                    }
                    ?.sortedBy { it.chapterIndex }
                    .orEmpty()

                if (chapters.isEmpty()) return@mapNotNull null

                PreviewBook(
                    bookKey = bookDir.name,
                    bookName = chapters.firstOrNull { it.title.isNotBlank() }
                        ?.let { readManifest(chapterDir(context, bookDir.name, it.chapterKey)) }
                        ?.optString("bookName", "")
                        ?.takeIf { it.isNotBlank() }
                        ?: chapters.firstOrNull()
                            ?.let { readManifest(chapterDir(context, bookDir.name, it.chapterKey)) }
                            ?.optString("bookName", "")
                            .orEmpty()
                            .ifBlank { bookDir.name },
                    chapters = chapters,
                    sizeBytes = bookDir.sizeBytes()
                )
            }
            ?.sortedBy { it.bookName }
            .orEmpty()
    }

    fun clearChapter(context: Context, bookKey: String, chapterKey: String): Boolean {
        val dir = chapterDir(context, bookKey, chapterKey)
        readManifest(dir)
            ?.optJSONObject("chapterMp3")
            ?.optString("path", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { File(it).delete() } }
        return dir.deleteRecursively()
    }

    fun clearAll(context: Context): Boolean {
        return cacheRoot(context).deleteRecursively()
    }

    fun appendPreviewLog(context: Context, source: String, message: String) {
        runCatching {
            val file = previewLogFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(Date())

            val safeSource = source.replace("|", "/").trim().ifBlank { "缓存工厂" }
            val safeMessage = message
                .replace("\n", " ")
                .replace("\r", " ")
                .trim()

            file.appendText("$time | $safeSource | $safeMessage\n", Charsets.UTF_8)
            trimPreviewLogFile(file)
        }.onFailure {
            logger.warn(it) { "append preview log failed" }
        }
    }

    fun listPreviewLogs(context: Context, limit: Int = 200): List<PreviewLog> {
        return runCatching {
            val file = previewLogFile(context)
            if (!file.exists()) return emptyList()

            file.readLines(Charsets.UTF_8)
                .takeLast(limit)
                .mapNotNull { line ->
                    val parts = line.split(" | ", limit = 3)
                    if (parts.size != 3) return@mapNotNull null
                    PreviewLog(
                        time = parts[0],
                        source = parts[1],
                        message = parts[2]
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun clearPreviewLogs(context: Context): Boolean {
        return runCatching {
            val file = previewLogFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText("", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun retryFailedItems(
        context: Context,
        bookKey: String,
        chapterKey: String,
        liveManager: MixSynthesizer?,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            val ok = runCatching {
                cacheWorkMutex.withLock {
                    val manager = getBackgroundManager(context, liveManager)
                    retryFailedItemsInternal(context, manager, bookKey, chapterKey)
                }
            }.onFailure {
                logger.warn(it) { "retry failed cache items failed" }
            }.getOrDefault(false)

            onFinished?.invoke(ok)
        }
    }

    fun retryItem(
        context: Context,
        bookKey: String,
        chapterKey: String,
        itemIndex: Int,
        liveManager: MixSynthesizer?,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            val ok = runCatching {
                cacheWorkMutex.withLock {
                    val manager = getBackgroundManager(context, liveManager)
                    retryItemsInternal(context, manager, bookKey, chapterKey, setOf(itemIndex))
                }
            }.onFailure {
                logger.warn(it) { "retry cache item failed" }
            }.getOrDefault(false)

            onFinished?.invoke(ok)
        }
    }

    fun cancelWarmup() {
        warmJob?.cancel()
        warmJob = null
        warming.set(false)
    }

    fun exportChapterMp3(
        context: Context,
        bookKey: String,
        chapterKey: String,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            val ok = runCatching {
                cacheWorkMutex.withLock {
                    exportChapterMp3Internal(context, bookKey, chapterKey)
                }
            }.onFailure {
                logger.warn(it) { "export chapter mp3 failed" }
                appendPreviewLog(
                    context = context,
                    source = "有声书导出",
                    message = "导出失败｜$chapterKey｜${it.message.orEmpty()}"
                )
            }.getOrDefault(false)

            onFinished?.invoke(ok)
        }
    }

    suspend fun generateAudiobookChapters(
        context: Context,
        bookName: String,
        bookUrl: String,
        chapters: List<AudiobookChapterInput>,
        onProgress: ((AudiobookGenerationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): AudiobookGenerationProgress {
        val cleanChapters = chapters.filter { it.chapterText.isNotBlank() }
        if (cleanChapters.isEmpty()) {
            return AudiobookGenerationProgress(
                status = "failed",
                message = "没有可生成的章节正文",
                totalChapters = 0,
                readyChapters = 0,
                failedChapters = 0,
                totalItems = 0,
                readyItems = 0,
                failedItems = 0
            ).also { onProgress?.invoke(it) }
        }

        return cacheWorkMutex.withLock {
            val appContext = context.applicationContext
            val manager = getBackgroundManager(appContext, null)
            val bookKey = bookUrl.ifBlank { bookName }.md5()

            fun push(status: String, message: String): AudiobookGenerationProgress {
                return generationProgress(
                    context = appContext,
                    bookKey = bookKey,
                    chapters = cleanChapters,
                    status = status,
                    message = message
                ).also { onProgress?.invoke(it) }
            }

            appendPreviewLog(
                context = appContext,
                source = "缓存队列",
                message = "有声书生成开始｜书=${bookName.ifBlank { bookKey }}｜章节=${cleanChapters.size}｜按章节顺序缓存并导出MP3"
            )
            push("pending", "TTS 已接收 ${cleanChapters.size} 章，等待生成")

            cleanChapters.forEachIndexed { position, input ->
                currentCoroutineContext().ensureActive()
                if (isCancelled?.invoke() == true) {
                    return@withLock push("cancelled", "有声书生成已取消")
                }

                val chapter = JSONObject()
                    .put("ok", true)
                    .put("bookName", bookName)
                    .put("bookUrl", bookUrl)
                    .put("chapterIndex", input.chapterIndex)
                    .put("chapterTitle", input.chapterTitle)
                    .put("chapterText", input.chapterText)
                    .put("text", input.chapterText)

                appendPreviewLog(
                    context = appContext,
                    source = "缓存队列",
                    message = "有声书章节开始｜${position + 1}/${cleanChapters.size}｜${input.chapterIndex} ${input.chapterTitle}"
                )
                push(
                    status = "analyzing",
                    message = "正在生成第 ${position + 1}/${cleanChapters.size} 章：${input.chapterTitle.ifBlank { input.chapterIndex.toString() }}"
                )

                cacheChapter(
                    context = appContext,
                    manager = manager,
                    bookKey = bookKey,
                    bookName = bookName,
                    chapter = chapter,
                    onProgress = {
                        push(
                            status = "caching_audio",
                            message = "正在缓存并导出第 ${position + 1}/${cleanChapters.size} 章：${input.chapterTitle.ifBlank { input.chapterIndex.toString() }}"
                        )
                    }
                )

                appendPreviewLog(
                    context = appContext,
                    source = "缓存队列",
                    message = "有声书章节完成｜${position + 1}/${cleanChapters.size}｜${input.chapterIndex} ${input.chapterTitle}"
                )
                push(
                    status = "caching_audio",
                    message = "已处理第 ${position + 1}/${cleanChapters.size} 章"
                )
            }

            val final = generationProgress(
                context = appContext,
                bookKey = bookKey,
                chapters = cleanChapters,
                status = "",
                message = ""
            )
            val finalStatus = when {
                final.failedChapters > 0 -> "failed"
                final.readyChapters >= final.totalChapters -> "ready"
                else -> "caching_audio"
            }
            val message = when (finalStatus) {
                "ready" -> "有声书缓存已生成：${final.readyChapters}/${final.totalChapters} 章"
                "failed" -> "有声书生成完成但有失败章节：失败 ${final.failedChapters} 章"
                else -> "有声书生成仍有未完成章节"
            }
            final.copy(status = finalStatus, message = message).also {
                appendPreviewLog(
                    context = appContext,
                    source = "缓存队列",
                    message = "有声书生成结束｜status=${it.status}｜章节=${it.readyChapters}/${it.totalChapters}｜句子=${it.readyItems}/${it.totalItems}"
                )
                onProgress?.invoke(it)
            }
        }
    }

    private fun exportChapterMp3Internal(
        context: Context,
        bookKey: String,
        chapterKey: String,
    ): Boolean {
        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: return false
        val queue = readQueue(dir)
        val items = manifest.optJSONArray("items") ?: JSONArray()
        val configFingerprint = manifest.optString("configFingerprint", currentConfigFingerprint())
        val chapterTitle = manifest.optString("chapterTitle", "")
        val chapterIndex = manifest.optInt("chapterIndex", -1)

        updateChapterMp3State(dir, "exporting", error = "")

        val exportItems = if (queue.isNotEmpty()) {
            queue.sortedBy { it.index }.map { queueItem ->
                findReadyMp3Item(items, queueItem, configFingerprint)
                    ?: return failChapterMp3Export(
                        dir = dir,
                        context = context,
                        chapterTitle = chapterTitle,
                        error = "第 ${queueItem.index} 句还没有可导出的 MP3 源音频，请先重试或重新缓存本章。"
                    )
            }
        } else {
            readyMp3Items(items)
        }

        if (exportItems.isEmpty()) {
            return failChapterMp3Export(
                dir = dir,
                context = context,
                chapterTitle = chapterTitle,
                error = "本章没有可导出的 MP3 源音频。"
            )
        }

        val outDir = chapterMp3Dir(context, manifest.optString("bookName", bookKey))
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(
            outDir,
            "${chapterIndex.toString().padStart(4, '0')}_${chapterTitle.safeFileName().ifBlank { "未命名章节" }}.mp3"
        )
        val tempFile = File(outFile.parentFile, "${outFile.name}.tmp")

        runCatching {
            tempFile.outputStream().use { output ->
                exportItems.forEachIndexed { index, item ->
                    val file = File(item.optString("sourceAudioPath", ""))
                    copyMp3Payload(file.readBytes(), output, keepLeadingTags = index == 0)
                }
            }
            if (tempFile.length() <= 0) error("导出文件为空")
            if (outFile.exists()) outFile.delete()
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
        }.onFailure {
            tempFile.delete()
            return failChapterMp3Export(
                dir = dir,
                context = context,
                chapterTitle = chapterTitle,
                error = it.message ?: "写入章节 MP3 失败"
            )
        }

        val latest = readManifest(dir) ?: manifest
        latest.put(
            "chapterMp3",
            JSONObject()
                .put("status", "ready")
                .put("path", outFile.absolutePath)
                .put("sizeBytes", outFile.length())
                .put("itemCount", exportItems.size)
                .put("updatedAt", System.currentTimeMillis())
        )
        latest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, latest)

        appendPreviewLog(
            context = context,
            source = "有声书导出",
            message = "导出完成｜$chapterIndex $chapterTitle｜${outFile.name}｜${outFile.length()} bytes"
        )
        return true
    }

    private fun failChapterMp3Export(
        dir: File,
        context: Context,
        chapterTitle: String,
        error: String,
    ): Boolean {
        updateChapterMp3State(dir, "failed", error = error)
        appendPreviewLog(
            context = context,
            source = "有声书导出",
            message = "导出失败｜$chapterTitle｜$error"
        )
        return false
    }

    private fun updateChapterMp3State(
        dir: File,
        status: String,
        error: String,
    ) {
        val manifest = readManifest(dir) ?: return
        val old = manifest.optJSONObject("chapterMp3") ?: JSONObject()
        old.put("status", status)
        old.put("updatedAt", System.currentTimeMillis())
        if (error.isNotBlank()) old.put("error", error) else old.remove("error")
        manifest.put("chapterMp3", old)
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
    }

    private fun findReadyMp3Item(
        items: JSONArray,
        queueItem: QueueItem,
        configFingerprint: String,
    ): JSONObject? {
        val textHash = queueItem.text.md5()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("index", -1) != queueItem.index && item.optString("textHash") != textHash) continue
            if (item.optString("configFingerprint") != configFingerprint) continue
            if (!item.matchesQueueItem(queueItem)) continue
            if (!item.hasUsableMp3Source()) continue
            return item
        }
        return null
    }

    private fun readyMp3Items(items: JSONArray): List<JSONObject> {
        return (0 until items.length())
            .mapNotNull { items.optJSONObject(it) }
            .filter { it.hasUsableMp3Source() }
            .sortedBy { it.optInt("index", 0) }
    }

    private fun JSONObject.hasUsableMp3Source(): Boolean {
        if (optString("status", "ready") != "ready") return false
        if (optString("sourceAudioFormat", "") != "mp3") return false
        val file = File(optString("sourceAudioPath", ""))
        return file.exists() && file.length() > 0
    }

    private fun generationProgress(
        context: Context,
        bookKey: String,
        chapters: List<AudiobookChapterInput>,
        status: String,
        message: String,
    ): AudiobookGenerationProgress {
        var readyChapters = 0
        var failedChapters = 0
        var totalItems = 0
        var readyItems = 0
        var failedItems = 0

        chapters.forEach { chapter ->
            val dir = chapterDir(context, bookKey, chapterKey(bookKey, chapter.chapterIndex))
            val manifest = readManifest(dir)
            val queue = readQueue(dir)

            val queueCount = queue.size.takeIf { it > 0 }
                ?: manifest?.optInt("queueSize", 0)
                ?: 0
            totalItems += queueCount
            readyItems += queue.count { it.raw.optString("status") == "ready" }
            failedItems += queue.count {
                it.raw.optString("status") == "failed" || it.raw.optString("error").isNotBlank()
            }

            val manifestStatus = manifest?.optString("status", "").orEmpty()
            val chapterMp3 = manifest?.optJSONObject("chapterMp3")
            val mp3Status = chapterMp3?.optString("status", "").orEmpty()
            when {
                manifestStatus == "ready" && mp3Status == "ready" -> readyChapters += 1
                manifestStatus == "failed" || mp3Status == "failed" -> failedChapters += 1
            }
        }

        return AudiobookGenerationProgress(
            status = status.ifBlank {
                when {
                    failedChapters > 0 -> "failed"
                    readyChapters >= chapters.size && chapters.isNotEmpty() -> "ready"
                    else -> "caching_audio"
                }
            },
            message = message,
            totalChapters = chapters.size,
            readyChapters = readyChapters,
            failedChapters = failedChapters,
            totalItems = totalItems,
            readyItems = readyItems,
            failedItems = failedItems
        )
    }

    private fun copyMp3Payload(
        bytes: ByteArray,
        output: OutputStream,
        keepLeadingTags: Boolean,
    ) {
        if (bytes.isEmpty()) return

        val end = bytes.stripTrailingId3v1End()
        var start = if (keepLeadingTags) 0 else bytes.skipLeadingId3v2()
        if (!keepLeadingTags) {
            start = bytes.findMp3FrameStart(start).takeIf { it >= 0 } ?: start
        }
        if (start < end) {
            output.write(bytes, start, end - start)
        }
    }

    private fun ByteArray.looksLikeMp3(): Boolean {
        if (size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() && this[2] == '3'.code.toByte()) {
            return true
        }
        return findMp3FrameStart(0).let { it in 0..1024 }
    }

    private fun ByteArray.skipLeadingId3v2(): Int {
        if (size < 10) return 0
        if (this[0] != 'I'.code.toByte() || this[1] != 'D'.code.toByte() || this[2] != '3'.code.toByte()) {
            return 0
        }
        val tagSize =
            ((this[6].toInt() and 0x7F) shl 21) or
                ((this[7].toInt() and 0x7F) shl 14) or
                ((this[8].toInt() and 0x7F) shl 7) or
                (this[9].toInt() and 0x7F)
        val hasFooter = (this[5].toInt() and 0x10) != 0
        return (10 + tagSize + if (hasFooter) 10 else 0).coerceAtMost(size)
    }

    private fun ByteArray.stripTrailingId3v1End(): Int {
        if (size < 128) return size
        val start = size - 128
        return if (
            this[start] == 'T'.code.toByte() &&
            this[start + 1] == 'A'.code.toByte() &&
            this[start + 2] == 'G'.code.toByte()
        ) start else size
    }

    private fun ByteArray.findMp3FrameStart(fromIndex: Int): Int {
        val start = fromIndex.coerceAtLeast(0)
        for (i in start until size - 1) {
            if ((this[i].toInt() and 0xFF) == 0xFF && (this[i + 1].toInt() and 0xE0) == 0xE0) {
                return i
            }
        }
        return -1
    }

    private suspend fun cacheChapter(
        context: Context,
        manager: MixSynthesizer,
        bookKey: String,
        bookName: String,
        chapter: JSONObject,
        onProgress: (() -> Unit)? = null,
    ) {
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        val configFingerprint = currentConfigFingerprint()
        manifest.put("status", "analyzing")
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)

        val queue = prepareQueue(context, manager, bookName, chapter).sortedBy { it.index }

        syncRoleManagerBookFiles(context, bookName, bookKey, chapter, queue)

        if (queue.isEmpty()) {
            manifest.put("status", "failed")
            manifest.put("error", "朗读规则没有生成台词本队列")
            manifest.put("updatedAt", System.currentTimeMillis())
            writeManifest(dir, manifest)
            onProgress?.invoke()
            return
        }

        writeQueue(dir, queue)
        manifest.put("status", "queue_ready")
        manifest.put("queueSize", queue.size)
        manifest.put("configFingerprint", configFingerprint)
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
        onProgress?.invoke()

        manifest.put("status", "caching_audio")
        manifest.put("configFingerprint", configFingerprint)
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
        onProgress?.invoke()

        appendPreviewLog(
            context = context,
            source = "缓存队列",
            message = "章节队列就绪｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜${queue.size}句｜按台词本顺序请求"
        )

        for (item in queue) {
            val latestManifest = readManifest(dir) ?: manifest
            if (hasItem(latestManifest, item, configFingerprint)) {
                updateQueueItem(dir, item.index, "ready")
                onProgress?.invoke()
                continue
            }

            updateQueueItem(dir, item.index, "caching_audio")
            appendPreviewLog(
                context = context,
                source = "缓存队列",
                message = "请求音频｜${chapter.optInt("chapterIndex", -1)}#${item.index.toString().padStart(2, '0')}｜${item.tag.ifBlank { "旁白" }}｜${item.voice.ifBlank { "默认音色" }}"
            )
            val config = resolveTtsConfig(manager, item)
            if (config == null) {
                updateQueueItem(dir, item.index, "failed", "No matching TTS config")
                onProgress?.invoke()
                appendPreviewLog(
                    context = context,
                    source = "缓存队列",
                    message = "请求失败｜${chapter.optInt("chapterIndex", -1)}#${item.index.toString().padStart(2, '0')}｜没有匹配音色"
                )
                continue
            }

            val audio = synthesizeQueueItemToPcm(manager, item, config)
            if (audio == null) {
                updateQueueItem(dir, item.index, "failed", "TTS request failed")
                onProgress?.invoke()
                appendPreviewLog(
                    context = context,
                    source = "缓存队列",
                    message = "请求失败｜${chapter.optInt("chapterIndex", -1)}#${item.index.toString().padStart(2, '0')}｜TTS 请求失败"
                )
                continue
            }
            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = bookName,
                chapter = chapter,
                index = item.index,
                text = item.text,
                sampleRate = audio.sampleRate,
                bytes = audio.pcmBytes,
                sourceBytes = audio.sourceBytes,
                sourceFormat = audio.sourceFormat,
                queueItem = item,
                status = "caching_audio"
            )
            updateQueueItem(dir, item.index, "ready")
            onProgress?.invoke()
        }

        val latest = readManifest(dir) ?: manifest
        val latestQueue = readQueue(dir)
        val finalStatus = if (latestQueue.any { it.raw.optString("status") == "failed" }) "failed" else "ready"
        latest.put("status", finalStatus)
        latest.remove("error")
        writeManifest(dir, latest)

        if (finalStatus == "ready") {
            exportChapterMp3Internal(context, bookKey, chapterKey)
        }
        onProgress?.invoke()
    }

    private suspend fun retryFailedItemsInternal(
        context: Context,
        manager: MixSynthesizer,
        bookKey: String,
        chapterKey: String,
    ): Boolean {
        val failedIndexes = chapterDir(context, bookKey, chapterKey)
            .let { readQueue(it) }
            .filter { it.raw.optString("status") == "failed" || it.raw.optString("error").isNotBlank() }
            .map { it.index }
            .toSet()

        if (failedIndexes.isEmpty()) return true

        return retryItemsInternal(context, manager, bookKey, chapterKey, failedIndexes)
    }

    private suspend fun retryItemsInternal(
        context: Context,
        manager: MixSynthesizer,
        bookKey: String,
        chapterKey: String,
        indexes: Set<Int>,
    ): Boolean {
        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: return false
        val queue = readQueue(dir)
            .filter { it.index in indexes }
            .sortedBy { it.index }

        if (queue.isEmpty()) return true

        manifest.put("status", "caching_audio")
        writeManifest(dir, manifest)

        queue.forEach { item ->
            updateQueueItem(dir, item.index, "caching_audio")
            val config = resolveTtsConfig(manager, item)
            if (config == null) {
                updateQueueItem(dir, item.index, "failed", "No matching TTS config")
                return@forEach
            }

            val audio = synthesizeQueueItemToPcm(manager, item, config)
            if (audio == null) {
                updateQueueItem(dir, item.index, "failed", "TTS request failed")
                return@forEach
            }

            val chapter = JSONObject()
                .put("chapterIndex", manifest.optInt("chapterIndex", -1))
                .put("chapterTitle", manifest.optString("chapterTitle", ""))
                .put("textHash", manifest.optString("textHash", ""))

            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = manifest.optString("bookName", ""),
                chapter = chapter,
                index = item.index,
                text = item.text,
                sampleRate = audio.sampleRate,
                bytes = audio.pcmBytes,
                sourceBytes = audio.sourceBytes,
                sourceFormat = audio.sourceFormat,
                queueItem = item,
                status = "caching_audio"
            )
            updateQueueItem(dir, item.index, "ready")
        }

        val latest = readManifest(dir) ?: manifest
        val finalStatus = if (readQueue(dir).any { it.raw.optString("status") == "failed" }) "failed" else "ready"
        latest.put("status", finalStatus)
        writeManifest(dir, latest)
        if (finalStatus == "ready") {
            exportChapterMp3Internal(context, bookKey, chapterKey)
        }
        return true
    }

    private suspend fun synthesizeQueueItemToPcm(
        manager: MixSynthesizer,
        item: QueueItem,
        config: TtsConfiguration,
    ): SynthesizedAudio? {
        val out = ByteArrayOutputStream()
        val sampleRate = config.audioFormat.sampleRate.coerceAtLeast(8000)
        val request = RequestPayload(SystemParams(text = item.text), config)
        var sourceBytes: ByteArray? = null
        var sourceFormat = ""

        var ok = false
        manager.ttsRequester.request(request.params, request.config)
            .onSuccess { resp ->
                val stream = resp.stream ?: return@onSuccess
                val rawBytes = stream.use { it.readBytes() }
                sourceBytes = rawBytes
                sourceFormat = if (rawBytes.looksLikeMp3()) "mp3" else ""
                manager.streamProcessor.processStream(
                    ins = rawBytes.inputStream(),
                    request = request,
                    targetSampleRate = sampleRate,
                    callback = { pcm ->
                        if (pcm.hasRemaining()) {
                            val bytes = ByteArray(pcm.remaining())
                            pcm.get(bytes)
                            out.write(bytes)
                        }
                    }
                ).onSuccess {
                    ok = true
                }
            }
            .onFailure {
                logger.warn { "queue request failed: ${item.index}, ${it}" }
            }

        return out.toByteArray()
            .takeIf { ok && it.isNotEmpty() }
            ?.let {
                SynthesizedAudio(
                    sampleRate = sampleRate,
                    pcmBytes = it,
                    sourceBytes = sourceBytes,
                    sourceFormat = sourceFormat
                )
            }
    }

    private suspend fun getBackgroundManager(
        context: Context,
        liveManager: MixSynthesizer?,
    ): MixSynthesizer {
        val current = backgroundManager
        if (current != null && current.isInitialized) return current

        val cfg = liveManager?.context?.cfg?.copy(
            bgmEnabled = { false },
            streamPlayEnabled = SysTtsConfig::isStreamPlayModeEnabled,
            audioParams = { currentAudioParams() }
        ) ?: SynthesizerConfig(
            requestTimeout = SysTtsConfig::requestTimeout,
            maxRetryTimes = SysTtsConfig::maxRetryCount,
            streamPlayEnabled = SysTtsConfig::isStreamPlayModeEnabled,
            silenceSkipEnabled = SysTtsConfig::isSkipSilentAudio,
            bgmShuffleEnabled = SysTtsConfig::isBgmShuffleEnabled,
            bgmVolume = SysTtsConfig::bgmVolume,
            bgmEnabled = { false },
            audioParams = { currentAudioParams() }
        )

        return MixSynthesizer(
            SynthesizerContext(
                androidContext = context.applicationContext,
                logger = logger,
                cfg = cfg
            )
        ).apply {
            textProcessor = TextProcessor()
            init()
            backgroundManager = this
        }
    }

    private fun currentAudioParams(): AudioParams {
        return AudioParams(
            speed = SysTtsConfig.audioParamsSpeed,
            volume = SysTtsConfig.audioParamsVolume,
            pitch = SysTtsConfig.audioParamsPitch
        )
    }

    private fun prepareQueue(
        context: Context,
        manager: MixSynthesizer,
        bookName: String,
        chapter: JSONObject,
    ): List<QueueItem> {
        val rule = findActiveSpeechRule()
        val chapterText = chapter.optString("text", "")

        val rawQueue = if (rule != null) {
            runCatching {
                SpeechRuleEngine(context, rule).apply { eval() }
                    .prepareChapterAudioQueueIfExists(
                        mapOf(
                            "bookName" to bookName,
                            "bookTitle" to bookName,
                            "name" to bookName,
                            "bookUrl" to chapter.optString("bookUrl", ""),
                            "bookKey" to chapter.optString("bookUrl", ""),
                            "chapterIndex" to chapter.optInt("chapterIndex", -1),
                            "chapterTitle" to chapter.optString("chapterTitle", ""),
                            "chapterName" to chapter.optString("chapterTitle", ""),
                            "chapterText" to chapterText,
                            "text" to chapterText
                        )
                    )
            }.onFailure {
                logger.warn(it) { "prepareChapterAudioQueue failed" }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        appendPreviewLog(
            context = context,
            source = "朗读规则",
            message = "规则队列原始返回｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜rawQueue=${rawQueue.size}"
        )

        rawQueue.take(5).forEachIndexed { i, raw ->
            appendPreviewLog(
                context = context,
                source = "朗读规则",
                message = "规则队列样本#$i｜keys=${raw.keys.joinToString(",")}｜raw=${raw.toString().take(500)}"
            )
        }

        val queue = rawQueue.mapIndexedNotNull { fallbackIndex, raw ->
            val text = raw.firstString("text", "content", "line", "sentence", "value", "台词", "内容")
            if (text.isBlank()) return@mapIndexedNotNull null

            val index = raw["index"]?.toString()?.toIntOrNull() ?: fallbackIndex
            val tag = raw.firstString(
                "tag",
                "role",
                "roleName",
                "speaker",
                "speakerName",
                "character",
                "characterName",
                "name",
                "人物",
                "角色",
                "说话人"
            )
            val voice = raw.firstString(
                "voice",
                "voiceName",
                "voiceId",
                "speakerVoice",
                "tts",
                "ttsName",
                "音色",
                "声音"
            )
            val emotion = raw.firstString("emotion", "emo", "style", "mood", "情绪", "感情")
            val speed = raw.firstFloat(1f, "speed", "rate", "语速")
            val pitch = raw.firstFloat(1f, "pitch", "tone", "音高")
            val volume = raw.firstFloat(1f, "volume", "vol", "音量")
            val normalizedRaw = JSONObject(raw)
                .put("index", index)
                .put("text", text)
                .put("tag", tag)
                .put("voice", voice)
                .put("emotion", emotion)
                .put("speed", speed)
                .put("pitch", pitch)
                .put("volume", volume)
                .put("status", raw.firstString("status", "state").ifBlank { "pending" })

            QueueItem(
                index = index,
                text = text,
                tag = tag,
                voice = voice,
                emotion = emotion,
                speed = speed,
                pitch = pitch,
                volume = volume,
                raw = normalizedRaw
            )
        }

        if (queue.isNotEmpty()) {
            val hasRuleResult = queue.any { item ->
                item.tag.isNotBlank() || item.voice.isNotBlank()
            }

            if (hasRuleResult) {
                return queue.sortedBy { it.index }
            }

            appendPreviewLog(
                context = context,
                source = "朗读规则",
                message = "${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")} 新队列缺少角色/音色结果，改用旧朗读规则兼容模式。"
            )
        }

        if (rule != null) {
            val legacyQueue = prepareLegacyRuleQueue(
                context = context,
                manager = manager,
                rule = rule,
                chapterText = chapterText,
                chapter = chapter
            )
            if (legacyQueue.isNotEmpty()) return legacyQueue

            appendPreviewLog(
                context = context,
                source = "朗读规则",
                message = "${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")} 未生成有效台词本队列，已停止本章缓存。"
            )
            return emptyList()
        }

        return splitSentences(chapterText).mapIndexed { index, sentence ->
            QueueItem(
                index = index,
                text = sentence,
                tag = "",
                voice = "",
                emotion = "",
                speed = 1f,
                pitch = 1f,
                volume = 1f,
                raw = JSONObject()
                    .put("index", index)
                    .put("text", sentence)
                    .put("status", "pending")
            )
        }
    }

    private fun prepareLegacyRuleQueue(
        context: Context,
        manager: MixSynthesizer,
        rule: SpeechRule,
        chapterText: String,
        chapter: JSONObject,
    ): List<QueueItem> {
        val configs = runCatching {
            manager.repo.getAllTts().map { (id, config) ->
                config.copy(speechInfo = config.speechInfo.copy(configId = id))
            }
        }.getOrDefault(emptyList())

        if (configs.isEmpty()) return emptyList()

        val fragments = runCatching {
            SpeechRuleEngine(context, rule).apply { eval() }
                .handleText(
                    text = chapterText,
                    list = configs.map { it.speechInfo },
                    ctxJson = JSONObject()
                        .put("chapterIndex", chapter.optInt("chapterIndex", -1))
                        .put("chapterTitle", chapter.optString("chapterTitle", ""))
                        .put("text", chapterText)
                        .toString()
                )
        }.onFailure {
            logger.warn(it) { "legacy handleText queue failed" }
            appendPreviewLog(
                context = context,
                source = "朗读规则",
                message = "旧规则兼容失败｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜${it.message.orEmpty()}"
            )
        }.getOrDefault(emptyList())

        val sourceItems = if (fragments.isNotEmpty()) {
            fragments.flatMap { fragment ->
                val config = configs.firstOrNull {
                    !it.speechInfo.isStandby &&
                        it.speechInfo.tag == fragment.tag &&
                        it.speechInfo.configId == fragment.id
                } ?: configs.firstOrNull {
                    !it.speechInfo.isStandby && it.speechInfo.tag == fragment.tag
                } ?: configs.firstOrNull()

                splitSentences(fragment.text).map { sentence ->
                    LegacyQueueSource(
                        text = sentence,
                        tag = fragment.tag,
                        voice = config?.source?.voice.orEmpty(),
                        configId = fragment.id
                    )
                }
            }
        } else {
            splitSentences(chapterText).map { sentence ->
                LegacyQueueSource(
                    text = sentence,
                    tag = "",
                    voice = configs.firstOrNull()?.source?.voice.orEmpty(),
                    configId = 0L
                )
            }
        }

        val queue = sourceItems
            .filter { it.text.isNotBlank() }
            .mapIndexed { index, source ->
                QueueItem(
                    index = index,
                    text = source.text,
                    tag = source.tag,
                    voice = source.voice,
                    emotion = "",
                    speed = 1f,
                    pitch = 1f,
                    volume = 1f,
                    raw = JSONObject()
                        .put("index", index)
                        .put("text", source.text)
                        .put("tag", source.tag)
                        .put("voice", source.voice)
                        .put("legacyConfigId", source.configId)
                        .put("queueMode", if (fragments.isNotEmpty()) "legacy_handleText" else "legacy_sentence")
                        .put("status", "pending")
                )
            }

        if (queue.isNotEmpty()) {
            appendPreviewLog(
                context = context,
                source = "朗读规则",
                message = "旧规则兼容模式｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜生成 ${queue.size} 句台词本"
            )
        }

        return queue
    }

    private fun resolveTtsConfig(manager: MixSynthesizer, item: QueueItem): TtsConfiguration? {
        val configs = runCatching {
            manager.repo.getAllTts().map { (id, config) ->
                config.copy(speechInfo = config.speechInfo.copy(configId = id))
            }
        }.getOrDefault(emptyList())
        if (configs.isEmpty()) return null

        val byLegacyId = item.raw.optLong("legacyConfigId", 0L).takeIf { it > 0L }?.let { id ->
            configs.firstOrNull { it.speechInfo.configId == id }
        }

        val byVoice = item.voice.takeIf { it.isNotBlank() }?.let { voice ->
            configs.firstOrNull {
                it.source.voice == voice ||
                    it.speechInfo.tagName == voice ||
                    it.speechInfo.tagData.values.any { value -> value == voice }
            }
        }

        val byTag = item.tag.takeIf { it.isNotBlank() }?.let { tag ->
            configs.firstOrNull {
                it.speechInfo.tag == tag ||
                    it.speechInfo.tagName == tag ||
                    it.speechInfo.tagData.values.any { value -> value == tag }
            }
        }

        val base = byLegacyId ?: byVoice ?: byTag ?: configs.firstOrNull()
        return base?.copy(
            audioParams = AudioParams(
                speed = item.speed.takeIf { it > 0f } ?: base.audioParams.speed,
                pitch = item.pitch.takeIf { it > 0f } ?: base.audioParams.pitch,
                volume = item.volume.takeIf { it > 0f } ?: base.audioParams.volume
            )
        )
    }

    private fun findActiveSpeechRule(): SpeechRule? {
        val ruleId = dbm.systemTtsV2.allEnabled
            .mapNotNull { (it.config as? TtsConfigurationDTO)?.speechRule?.tagRuleId }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val exact = dbm.speechRuleDao.getByRuleId(ruleId)
        if (exact != null) {
            if (!exact.isModule) return exact

            return dbm.speechRuleDao.all.firstOrNull {
                it.projectId == exact.projectId && !it.isModule &&
                    (it.moduleId == "main" || it.moduleType == "main" || it.moduleType == "pipeline_entry")
            } ?: dbm.speechRuleDao.all.firstOrNull {
                it.projectId == exact.projectId && !it.isModule
            }
        }

        val baseId = normalizeSpeechRuleLookupId(ruleId)
        val fallbackRule = dbm.speechRuleDao.allEnabled.firstOrNull {
            !it.isModule && normalizeSpeechRuleLookupId(it.ruleId) == baseId
        }
        if (fallbackRule != null) return fallbackRule

        val fallbackModule = dbm.speechRuleDao.all.firstOrNull {
            it.isModule && normalizeSpeechRuleLookupId(it.ruleId) == baseId
        } ?: return null

        return dbm.speechRuleDao.all.firstOrNull {
            it.projectId == fallbackModule.projectId && !it.isModule &&
                (it.moduleId == "main" || it.moduleType == "main" || it.moduleType == "pipeline_entry")
        } ?: dbm.speechRuleDao.all.firstOrNull {
            it.projectId == fallbackModule.projectId && !it.isModule
        }
    }

    private fun normalizeSpeechRuleLookupId(ruleId: String): String {
        val value = ruleId.trim()
        if (value.isBlank()) return value

        val regex = Regex(
            pattern = """([._-](db|dev|new|test|debug|bak|backup|manual)(_\d{3})?)$""",
            option = RegexOption.IGNORE_CASE
        )

        return value.replace(regex, "")
    }

    private fun locateChapterForText(window: JSONObject, text: String): JSONObject? {
        val chapters = window.optJSONArray("chapters") ?: return null
        val clean = text.trim()

        for (i in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(i) ?: continue
            if (chapter.optString("text", "").contains(clean)) return chapter
        }

        return chapters.optJSONObject(0)
    }

    private fun nextIndexForPlayback(context: Context, bookKey: String, chapter: JSONObject): Int {
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val manifest = readManifest(chapterDir(context, bookKey, chapterKey)) ?: return 0
        return manifest.optJSONArray("items")?.length() ?: 0
    }

    private fun saveItem(
        context: Context,
        bookKey: String,
        bookName: String,
        chapter: JSONObject,
        index: Int,
        text: String,
        sampleRate: Int,
        bytes: ByteArray,
        sourceBytes: ByteArray? = null,
        sourceFormat: String = "",
        queueItem: QueueItem? = null,
        status: String,
    ) {
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        if (!dir.exists()) dir.mkdirs()

        val configFingerprint = currentConfigFingerprint()
        val audioFile = File(dir, "${index}_${text.md5()}_${configFingerprint.take(12)}.pcm")
        audioFile.writeBytes(bytes)

        val sourceAudioFile = sourceBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { rawBytes ->
                val ext = if (sourceFormat == "mp3") "mp3" else "audio"
                File(dir, "${index}_${text.md5()}_${configFingerprint.take(12)}.$ext")
                    .also { it.writeBytes(rawBytes) }
            }

        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        val items = manifest.optJSONArray("items") ?: JSONArray().also { manifest.put("items", it) }
        val item = JSONObject()
                .put("index", index)
                .put("text", text)
                .put("textHash", text.md5())
                .put("lookupTextHash", text.lookupTextKey().md5())
                .put("tag", queueItem?.tag.orEmpty())
                .put("voice", queueItem?.voice.orEmpty())
                .put("emotion", queueItem?.emotion.orEmpty())
                .put("speed", queueItem?.speed ?: 1f)
                .put("pitch", queueItem?.pitch ?: 1f)
                .put("volume", queueItem?.volume ?: 1f)
                .put("configFingerprint", configFingerprint)
                .put("sampleRate", sampleRate.coerceAtLeast(8000))
                .put("path", audioFile.absolutePath)
                .put("status", "ready")
                .put("updatedAt", System.currentTimeMillis())
        if (sourceAudioFile != null) {
            item.put("sourceAudioPath", sourceAudioFile.absolutePath)
            item.put("sourceAudioFormat", sourceFormat.ifBlank { "unknown" })
        }
        upsertItem(items = items, item = item)
        manifest.put("bookName", bookName)
        manifest.put("configFingerprint", configFingerprint)
        manifest.put("status", status)
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
    }

    private fun writeQueue(dir: File, queue: List<QueueItem>) {
        val arr = JSONArray()
        queue.sortedBy { it.index }.forEach { item ->
            val obj = JSONObject(item.raw.toString())
                .put("index", item.index)
                .put("text", item.text)
                .put("tag", item.tag)
                .put("voice", item.voice)
                .put("emotion", item.emotion)
                .put("speed", item.speed)
                .put("pitch", item.pitch)
                .put("volume", item.volume)
                .put("status", item.raw.optString("status", "pending"))
            arr.put(obj)
        }
        if (!dir.exists()) dir.mkdirs()
        File(dir, "queue.json").writeText(arr.toString(2), Charsets.UTF_8)
    }

    private fun readQueue(dir: File): List<QueueItem> {
        val file = File(dir, "queue.json")
        if (!file.exists()) return emptyList()

        val arr = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList()

        return (0 until arr.length()).mapNotNull { i ->
            val raw = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = raw.optString("text", "").trim()
            if (text.isBlank()) return@mapNotNull null

            QueueItem(
                index = raw.optInt("index", i),
                text = text,
                tag = raw.optString("tag", ""),
                voice = raw.optString("voice", ""),
                emotion = raw.optString("emotion", ""),
                speed = raw.optDouble("speed", 1.0).toFloat(),
                pitch = raw.optDouble("pitch", 1.0).toFloat(),
                volume = raw.optDouble("volume", 1.0).toFloat(),
                raw = raw
            )
        }
    }

    private fun updateQueueItem(dir: File, index: Int, status: String, error: String = "") {
        val file = File(dir, "queue.json")
        if (!file.exists()) return

        val arr = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optInt("index", -1) != index) continue
            item.put("status", status)
            item.put("updatedAt", System.currentTimeMillis())
            if (error.isNotBlank()) item.put("error", error)
            else item.remove("error")
            arr.put(i, item)
            break
        }
        file.writeText(arr.toString(2), Charsets.UTF_8)
    }

    private fun upsertItem(items: JSONArray, item: JSONObject) {
        val index = item.optInt("index", -1)
        val textHash = item.optString("textHash", "")
        val configFingerprint = item.optString("configFingerprint", "")
        for (i in 0 until items.length()) {
            val old = items.optJSONObject(i) ?: continue
            val sameIndex = old.optInt("index", -2) == index
            val sameText = old.optString("textHash") == textHash
            val sameConfig = old.optString("configFingerprint") == configFingerprint
            if ((sameIndex || sameText) && sameConfig) {
                items.put(i, item)
                return
            }
        }
        items.put(item)
    }

    private fun hasItem(
        manifest: JSONObject,
        queueItem: QueueItem,
        configFingerprint: String,
    ): Boolean {
        val items = manifest.optJSONArray("items") ?: return false
        val textHash = queueItem.text.md5()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("index", -1) != queueItem.index && item.optString("textHash") != textHash) continue
            if (item.optString("configFingerprint") != configFingerprint) continue
            if (!item.matchesQueueItem(queueItem)) continue
            val file = File(item.optString("path", ""))
            val sourcePath = item.optString("sourceAudioPath", "")
            val sourceFile = File(sourcePath)
            val sourceReady = sourcePath.isNotBlank() && sourceFile.exists() && sourceFile.length() > 0
            return file.exists() && file.length() > 0 && sourceReady
        }
        return false
    }

    private fun newManifest(bookName: String, chapter: JSONObject, chapterKey: String): JSONObject {
        return JSONObject()
            .put("chapterKey", chapterKey)
            .put("status", "pending")
            .put("bookName", bookName)
            .put("chapterIndex", chapter.optInt("chapterIndex", -1))
            .put("chapterTitle", chapter.optString("chapterTitle", ""))
            .put("textHash", chapter.optString("textHash", chapter.optString("text", "").md5()))
            .put("configFingerprint", currentConfigFingerprint())
            .put("items", JSONArray())
            .put("createdAt", System.currentTimeMillis())
            .put("updatedAt", System.currentTimeMillis())
    }

    private fun readManifest(dir: File): JSONObject? {
        val file = File(dir, "manifest.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeManifest(dir: File, manifest: JSONObject) {
        if (!dir.exists()) dir.mkdirs()
        File(dir, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
    }

    private fun chapterDir(context: Context, bookKey: String, chapterKey: String): File {
        return File(cacheRoot(context), "$bookKey/$chapterKey")
    }

    private fun roleManagerDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "plugins/mingwuyan")
    }

    private fun safeRoleBookName(bookName: String): String {
        return bookName
            .trim()
            .ifBlank { "默认" }
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .replace(Regex("""^\.+"""), "")
            .ifBlank { "默认" }
    }

    private fun syncRoleManagerBookFiles(
        context: Context,
        bookName: String,
        bookKey: String,
        chapter: JSONObject,
        queue: List<QueueItem>,
    ) {
        runCatching {
            val safeBookName = safeRoleBookName(bookName)
            val dir = roleManagerDir(context)
            if (!dir.exists()) dir.mkdirs()

            val bookFile = File(dir, "shuming.$safeBookName.json")
            val existingBookRecords = readRoleRecords(bookFile)
            val globalRecords = readRoleRecords(File(dir, "characterRecords.json"))
            val mergedRecords = mergeRoleRecords(
                base = if (existingBookRecords.length() > 0) existingBookRecords else globalRecords,
                queue = queue
            )

            writeRoleBookList(dir, safeBookName)
            File(dir, "cunfang.txt").writeText(safeBookName, Charsets.UTF_8)

            if (mergedRecords.length() > 0) {
                val text = mergedRecords.toString(2)
                bookFile.writeText(text, Charsets.UTF_8)
                File(dir, "characterRecords.json").writeText(text, Charsets.UTF_8)
                File(dir, "gengxin.json").writeText(text, Charsets.UTF_8)
            } else if (!bookFile.exists()) {
                bookFile.writeText("[]", Charsets.UTF_8)
            }

            File(dir, "gengxin_meta.json").writeText(
                JSONObject()
                    .put("bookKey", bookKey)
                    .put("bookName", safeBookName)
                    .put("chapterKey", chapterKey(bookKey, chapter.optInt("chapterIndex", -1)))
                    .put("chapterTitle", chapter.optString("chapterTitle", ""))
                    .put("reason", "ttsCacheFactory")
                    .put("rolesCount", mergedRecords.length())
                    .put("updatedAt", System.currentTimeMillis())
                    .toString(2),
                Charsets.UTF_8
            )

            appendPreviewLog(
                context = context,
                source = "角色管理同步",
                message = "书=$safeBookName｜角色=${mergedRecords.length()}｜章节=${chapter.optString("chapterTitle", "")}"
            )
        }.onFailure {
            logger.warn(it) { "sync role manager book files failed" }
            appendPreviewLog(
                context = context,
                source = "角色管理同步",
                message = "同步失败：${it.message.orEmpty()}"
            )
        }
    }

    private fun readRoleRecords(file: File): JSONArray {
        return runCatching {
            if (!file.exists()) return JSONArray()
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.startsWith("[")) JSONArray(text) else JSONArray()
        }.getOrDefault(JSONArray())
    }

    private fun writeRoleBookList(dir: File, bookName: String) {
        val file = File(dir, "liebiao.json")
        val list = runCatching {
            if (file.exists()) JSONArray(file.readText(Charsets.UTF_8)) else JSONArray()
        }.getOrDefault(JSONArray())

        val names = linkedSetOf("默认")
        for (i in 0 until list.length()) {
            list.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { names += it }
        }
        names += bookName

        val out = JSONArray()
        names.forEach { out.put(it) }
        file.writeText(out.toString(2), Charsets.UTF_8)
    }

    private fun mergeRoleRecords(base: JSONArray, queue: List<QueueItem>): JSONArray {
        val byName = linkedMapOf<String, JSONObject>()

        for (i in 0 until base.length()) {
            val record = base.optJSONObject(i) ?: continue
            val name = record.optString("name", "").trim()
            if (name.isBlank()) continue
            byName[name] = JSONObject(record.toString()).also { normalizeRoleRecordForPlugin(it) }
        }

        queue
            .filter { it.tag.isNotBlank() }
            .filterNot { it.tag == "旁白" || it.tag == "narration" || it.tag == "未知发言人" }
            .groupBy { it.tag }
            .forEach { (name, items) ->
                val old = byName[name] ?: JSONObject().put("name", name)
                val voice = items.firstNotNullOfOrNull { it.voice.takeIf(String::isNotBlank) }
                    ?: old.optString("voice", "")

                old.put("name", name)
                normalizeRoleRecordForPlugin(old)
                if (!old.has("aliases")) old.put("aliases", "")
                if (voice.isNotBlank()) old.put("voice", voice)
                if (!old.has("gender")) old.put("gender", "")
                if (!old.has("genderAge")) old.put("genderAge", old.optString("age", ""))
                if (!old.has("age")) old.put("age", old.optString("genderAge", ""))
                old.put("usageCount", old.optInt("usageCount", 0) + items.size)
                if (!old.has("genderAgeHistory")) old.put("genderAgeHistory", JSONArray())

                byName[name] = old
            }

        val out = JSONArray()
        byName.values.forEach { out.put(it) }
        return out
    }

    private fun normalizeRoleRecordForPlugin(record: JSONObject) {
        val aliases = record.opt("aliases")
        if (aliases is JSONArray) {
            val values = mutableListOf<String>()
            for (i in 0 until aliases.length()) {
                aliases.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { values += it }
            }
            record.put("aliases", values.joinToString("|"))
        } else if (!record.has("aliases") || record.isNull("aliases")) {
            record.put("aliases", "")
        }
    }

    private fun cacheRoot(context: Context): File {
        return context.getExternalFilesDir("reader_audio_cache")
            ?: File(context.filesDir, "reader_audio_cache")
    }

    private fun chapterMp3Dir(context: Context, bookName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "audiobook_mp3")
        return File(root, "J.TTS有声书/${bookName.safeFileName().ifBlank { "默认" }}")
    }

    private fun previewLogFile(context: Context): File {
        return File(cacheRoot(context), "cache_factory.log")
    }

    private fun trimPreviewLogFile(file: File) {
        if (!file.exists()) return
        if (file.length() <= 1024 * 1024L) return

        val keepLines = file.readLines(Charsets.UTF_8).takeLast(2000)
        file.writeText(keepLines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun chapterKey(bookKey: String, chapterIndex: Int): String {
        return "${bookKey}_$chapterIndex"
    }

    private fun splitSentences(text: String): List<String> {
        return StringUtils.splitSentences(text)
            .map { it.trim() }
            .filter { it.any { ch -> ch.isLetterOrDigit() } }
            .distinct()
    }

    private fun currentConfigFingerprint(): String {
        return runCatching {
            val groups = dbm.systemTtsV2.getAllGroupWithTts()
            val plugins = dbm.pluginDao.all.associate { it.pluginId to it.userVars.toString() }

            buildString {
                append("global:")
                append(SysTtsConfig.audioParamsSpeed)
                append('|')
                append(SysTtsConfig.audioParamsPitch)
                append('|')
                append(SysTtsConfig.audioParamsVolume)
                append('|')
                append(SysTtsConfig.isSkipSilentAudio)
                append(';')

                groups.forEach { group ->
                    append("group:")
                    append(group.group.id)
                    append(':')
                    append(group.group.audioParams)
                    append(';')

                    group.list
                        .filter { it.isEnabled }
                        .sortedBy { it.id }
                        .forEach { tts ->
                            append("tts:")
                            append(tts.id)
                            append(':')
                            append(tts.groupId)
                            append(':')
                            append(tts.order)
                            append(':')
                            append(tts.displayName)
                            append(':')
                            append(tts.config)

                            val source = (tts.config as? TtsConfigurationDTO)?.source
                            val pluginId = (source as? PluginTtsSource)?.pluginId.orEmpty()
                            append(':')
                            append(pluginId)
                            append(':')
                            append(plugins[pluginId].orEmpty())
                            append(';')
                        }
                }
            }.md5()
        }.getOrDefault("unknown")
    }

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String.lookupTextKey(): String {
        return trim().filterNot { it.isWhitespace() }
    }

    private fun String.safeFileName(): String {
        return trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .replace(Regex("""^\.+"""), "")
            .take(80)
            .trim()
    }

    private fun previewChapter(bookKey: String, dir: File): PreviewChapter? {
        val manifest = readManifest(dir)
        val queue = readQueue(dir)
        if (manifest == null && queue.isEmpty()) return null

        val manifestItems = manifest?.optJSONArray("items")
        val readyItems = mutableListOf<JSONObject>()
        if (manifestItems != null) {
            for (i in 0 until manifestItems.length()) {
                val item = manifestItems.optJSONObject(i) ?: continue
                val path = item.optString("path", "")
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    readyItems += item
                }
            }
        }

        val queueItems = if (queue.isNotEmpty()) {
            queue.map { item ->
                val cached = readyItems.firstOrNull { it.matchesQueueItem(item) }
                PreviewItem(
                    index = item.index,
                    text = item.text,
                    tag = item.tag,
                    voice = item.voice,
                    status = if (cached != null) "ready" else item.raw.optString("status", "pending"),
                    emotion = item.emotion,
                    speed = item.speed,
                    pitch = item.pitch,
                    volume = item.volume,
                    error = item.raw.optString("error", "")
                )
            }
        } else {
            readyItems.map { item ->
                PreviewItem(
                    index = item.optInt("index", 0),
                    text = item.optString("text", ""),
                    tag = item.optString("tag", ""),
                    voice = item.optString("voice", ""),
                    status = item.optString("status", "ready"),
                    emotion = item.optString("emotion", ""),
                    speed = item.optDouble("speed", 1.0).toFloat(),
                    pitch = item.optDouble("pitch", 1.0).toFloat(),
                    volume = item.optDouble("volume", 1.0).toFloat(),
                    error = item.optString("error", "")
                )
            }
        }.sortedBy { it.index }

        val readyCount = queueItems.count { it.status == "ready" }
        val failedCount = queueItems.count { it.status == "failed" || it.error.isNotBlank() }
        val chapterMp3 = manifest?.optJSONObject("chapterMp3")
        val mp3Path = chapterMp3?.optString("path", "").orEmpty()
        val mp3File = File(mp3Path)

        return PreviewChapter(
            bookKey = bookKey,
            chapterKey = dir.name,
            chapterIndex = manifest?.optInt("chapterIndex", -1) ?: dir.name.substringAfterLast("_").toIntOrNull() ?: -1,
            title = manifest?.optString("chapterTitle", "").orEmpty(),
            status = manifest?.optString("status", "").orEmpty().ifBlank {
                when {
                    failedCount > 0 -> "failed"
                    queueItems.isNotEmpty() && readyCount == queueItems.size -> "ready"
                    queueItems.any { it.status == "caching_audio" } -> "caching_audio"
                    else -> "pending"
                }
            },
            items = queueItems,
            readyCount = readyCount,
            failedCount = failedCount,
            sizeBytes = dir.sizeBytes(),
            updatedAt = manifest?.optLong("updatedAt", 0L) ?: dir.lastModified(),
            mp3Status = chapterMp3?.optString("status", "").orEmpty(),
            mp3Path = mp3Path,
            mp3SizeBytes = if (mp3File.exists()) mp3File.length() else chapterMp3?.optLong("sizeBytes", 0L) ?: 0L,
            mp3Error = chapterMp3?.optString("error", "").orEmpty(),
        )
    }

    private fun File.sizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.sizeBytes() } ?: 0L
    }

    private fun Map<String, Any?>.firstString(vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun Map<String, Any?>.firstFloat(default: Float, vararg keys: String): Float {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.toString()?.toFloatOrNull()?.takeIf { it > 0f }
        } ?: default
    }

    private fun JSONObject.matchesQueueItem(item: QueueItem): Boolean {
        return optString("textHash", "") == item.text.md5() &&
            optString("tag", "") == item.tag &&
            optString("voice", "") == item.voice &&
            optString("emotion", "") == item.emotion &&
            optDouble("speed", 1.0).toFloat() == item.speed &&
            optDouble("pitch", 1.0).toFloat() == item.pitch &&
            optDouble("volume", 1.0).toFloat() == item.volume
    }
}
