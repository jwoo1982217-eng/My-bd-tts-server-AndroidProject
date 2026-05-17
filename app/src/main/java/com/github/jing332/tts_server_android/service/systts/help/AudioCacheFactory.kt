package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import android.net.Uri
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.MixSynthesizer
import com.github.jing332.tts.SynthesizerContext
import com.github.jing332.tts.synthesizer.RequestPayload
import com.github.jing332.tts.synthesizer.CacheRequestPayloadRecorder
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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

    // 开源阅读播放时会一句一句调用系统TTS。
    // 后台整章缓存只能低频触发，避免每一句都重复启动朗读规则整章分析。
    private const val WARM_REQUEST_INTERVAL_MS = 15 * 1000L

    // 播放缓存清理策略：保留当前句前面多少句，避免回退/重播又重新合成
    private const val PLAYBACK_CACHE_KEEP_BEHIND = 8

    // 当前书音频缓存上限。想更省空间可改成 80L * 1024L * 1024L
    private const val PLAYBACK_CACHE_BOOK_LIMIT_BYTES: Long = 256L * 1024L * 1024L

    // 最终分工：
    // J.TTS 播放链路不再读取旧音频缓存，避免误命中导致反复读同一句。
    // J.TTS 仍然可以合成当前句并写入缓存。
    // J阅读通过 EXPORT_READER_AUDIO_CACHE 复制缓存片段，自己拼接/加密整章音频。
    private const val DISABLE_PLAYBACK_CACHE_READ_FOR_STABILITY = true

    @Volatile
    private var lastWarmRequestAt: Long = 0L

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


    private fun writeCurrentBookLightBridge(context: Context, bookName: String, chapter: JSONObject) {
        val safeBookName = bookName.trim().ifBlank { "默认" }

        // 角色管理插件已改为直接读取 J.阅读 current-chapter。
        // APK 不再写 /storage/emulated/0/TTSvoices/role_manager_shared，避免 Android 13+ 公共目录 EPERM。
        appendPreviewLog(
            context = context,
            source = "当前书桥接",
            message = "已跳过APK公共目录写入｜书=$safeBookName｜章=${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜角色管理改为HTTP直读"
        )
    }

    fun getCachedAudio(context: Context, text: String): CachedAudio? {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return null

        if (DISABLE_PLAYBACK_CACHE_READ_FOR_STABILITY) {
            return null
        }

        // 逐句缓存按章节归档：只读取当前章元信息，不使用整章窗口
        val current = ReaderChapterBridgeClient.fetchCurrentChapterJson()
        if (!current.optBoolean("ok", false)) return null

        val bookName = current.optString("bookName", "").trim().ifBlank { "默认" }
        writeCurrentBookLightBridge(context, bookName, current)

        val bookKey = current.optString("bookUrl", bookName).md5()
        val chapterKey = chapterKey(bookKey, current.optInt("chapterIndex", -1))
        val manifest = readManifest(chapterDir(context, bookKey, chapterKey)) ?: return null
        val items = manifest.optJSONArray("items") ?: return null

        val textHash = cleanText.md5()
        val lookupTextHash = cleanText.lookupTextKey().md5()
        val configFingerprint = currentConfigFingerprint()
        val currentPointer = readCurrentPointerForAudioCache(context, cleanText)

        for (j in 0 until items.length()) {
            val item = items.optJSONObject(j) ?: continue
            val cachedLookupTextHash = item.optString("lookupTextHash", "").ifBlank {
                item.optString("text", "").lookupTextKey().md5()
            }

            val pointerMatched = isPointerMatchedForAudioCache(item, currentPointer)
            val textMatched = item.optString("textHash") == textHash || cachedLookupTextHash == lookupTextHash

            if (!pointerMatched && !textMatched) continue
            if (item.optString("configFingerprint") != configFingerprint) continue

            val file = File(item.optString("path", ""))
            if (!file.exists() || file.length() <= 0) continue

            val cachedIndex = item.optInt("index", -1)

                trimPlayedAudioBefore(context, bookKey, chapterKey, cachedIndex)
                trimCurrentBookAudioCache(context, bookKey, chapterKey)

                return CachedAudio(
                sampleRate = item.optInt("sampleRate", 16000).coerceAtLeast(8000),
                bytes = file.readBytes(),
                chapterKey = chapterKey,
                index = cachedIndex,
                voice = item.optString("actualVoice", "")
                    .ifBlank { item.optString("voice", "") }
                    .ifBlank { item.optString("tag", "") },
            )
        }

        return null
    }



    private fun readCurrentPointerForAudioCache(context: Context, currentText: String? = null): JSONObject? {
        return runCatching {
            val root = context.getExternalFilesDir(null) ?: return null

            var best: File? = null
            root.walkTopDown()
                .filter { it.isFile && it.name == "jread_current_pointer.json" }
                .forEach { file ->
                    if (best == null || file.lastModified() > (best?.lastModified() ?: 0L)) {
                        best = file
                    }
                }

            val f = best ?: return null
            val obj = JSONObject(f.readText())

            val ptrText = obj.optString("currentText", "").trim()
            val cur = currentText.orEmpty().trim()

            if (ptrText.isNotBlank() && cur.isNotBlank()) {
                obj.put(
                    "_pointerTextMatched",
                    ptrText == cur || ptrText.lookupTextKey() == cur.lookupTextKey()
                )
            } else {
                obj.put("_pointerTextMatched", false)
            }

            obj
        }.getOrNull()
    }

    private fun isPointerMatchedForAudioCache(item: JSONObject, pointer: JSONObject?): Boolean {
        if (pointer == null) return false

        val start = pointer.optInt("startOffset", -1)
        val end = pointer.optInt("endOffset", -1)
        if (start < 0 || end < start) return false

        val itemStart = item.optInt("startOffset", -1)
        val itemEnd = item.optInt("endOffset", -1)
        if (itemStart != start || itemEnd != end) return false

        val pContentHash = pointer.optString("contentHash", "")
        val iContentHash = item.optString("contentHash", "")
        if (pContentHash.isNotBlank() && iContentHash.isNotBlank() && pContentHash != iContentHash) {
            return false
        }

        val pSessionId = pointer.optString("sessionId", "")
        val iSessionId = item.optString("sessionId", "")
        if (pSessionId.isNotBlank() && iSessionId.isNotBlank() && pSessionId != iSessionId) {
            return false
        }

        val pChapterIndex = pointer.optInt("chapterIndex", -1)
        val iChapterIndex = item.optInt("chapterIndex", -1)
        if (pChapterIndex >= 0 && iChapterIndex >= 0 && pChapterIndex != iChapterIndex) {
            return false
        }

        return true
    }

    private fun putPointerFieldsForAudioCache(context: Context, currentText: String, item: JSONObject) {
        val pointer = readCurrentPointerForAudioCache(context, currentText) ?: return

        val start = pointer.optInt("startOffset", -1)
        val end = pointer.optInt("endOffset", -1)

        if (start >= 0) item.put("startOffset", start)
        if (end >= 0) item.put("endOffset", end)

        val sessionId = pointer.optString("sessionId", "")
        if (sessionId.isNotBlank()) item.put("sessionId", sessionId)

        val contentHash = pointer.optString("contentHash", "")
        if (contentHash.isNotBlank()) item.put("contentHash", contentHash)

        val chapterIndex = pointer.optInt("chapterIndex", -1)
        if (chapterIndex >= 0) item.put("chapterIndex", chapterIndex)

        val pointerText = pointer.optString("currentText", "")
        if (pointerText.isNotBlank()) item.put("pointerText", pointerText)

        item.put("pointerUpdatedAt", pointer.optLong("updatedAt", System.currentTimeMillis()))
        item.put("pointerTextMatched", pointer.optBoolean("_pointerTextMatched", false))
    }


    private fun isAudioCacheFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        return name.endsWith(".pcm") || name.endsWith(".mp3") || name.endsWith(".wav")
    }

    private fun trimPlayedAudioBefore(
        context: Context,
        bookKey: String,
        chapterKey: String,
        currentIndex: Int,
    ) {
        if (currentIndex < 0) return

        val deleteBefore = currentIndex - PLAYBACK_CACHE_KEEP_BEHIND
        if (deleteBefore <= 0) return

        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: return
        val items = manifest.optJSONArray("items") ?: return

        var deletedCount = 0
        var deletedBytes = 0L

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)

            if (index >= 0 && index < deleteBefore) {
                val path = item.optString("path", "")
                if (path.isBlank()) continue

                val file = File(path)
                if (file.exists() && file.isFile && isAudioCacheFile(file)) {
                    val len = file.length()
                    if (file.delete()) {
                        deletedCount++
                        deletedBytes += len
                    }
                }
            }
        }

        if (deletedCount > 0) {
            appendPreviewLog(
                context = context,
                source = "播放缓存清理",
                message = "已清理前文缓存｜章=$chapterKey｜当前句=$currentIndex｜删除=$deletedCount 个｜释放=${formatAudioCacheSize(deletedBytes)}"
            )
        }
    }

    private fun trimCurrentBookAudioCache(
        context: Context,
        bookKey: String,
        chapterKey: String,
        maxBytes: Long = PLAYBACK_CACHE_BOOK_LIMIT_BYTES,
    ) {
        val bookDir = chapterDir(context, bookKey, chapterKey).parentFile ?: return
        if (!bookDir.exists()) return

        val files = bookDir.walkTopDown()
            .filter { it.isFile && isAudioCacheFile(it) }
            .toList()

        var totalBytes = 0L
        for (file in files) {
            totalBytes += file.length()
        }

        if (totalBytes <= maxBytes) return

        val sortedFiles = files.sortedBy { it.lastModified() }

        var deletedCount = 0
        var deletedBytes = 0L

        for (file in sortedFiles) {
            if (totalBytes <= maxBytes) break

            val len = file.length()
            if (file.delete()) {
                totalBytes -= len
                deletedBytes += len
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            appendPreviewLog(
                context = context,
                source = "播放缓存清理",
                message = "当前书缓存超限｜上限=${formatAudioCacheSize(maxBytes)}｜删除=$deletedCount 个｜释放=${formatAudioCacheSize(deletedBytes)}"
            )
        }
    }


    fun savePlaybackAudio(
        context: Context,
        text: String,
        sampleRate: Int,
        bytes: ByteArray,
        request: RequestPayload? = null,
    ) {
        if (text.isBlank() || bytes.isEmpty()) {
            appendPreviewLog(
                context = context,
                source = "章节归档",
                message = "跳过｜textBlank=${text.isBlank()}｜bytes=${bytes.size}"
            )
            return
        }

        scope.launch {
            runCatching {
                appendPreviewLog(
                    context = context,
                    source = "章节归档",
                    message = "开始｜text=${text.take(40)}｜bytes=${bytes.size}"
                )

                val current = ReaderChapterBridgeClient.fetchCurrentChapterJson()
                if (!current.optBoolean("ok", false)) {
                    appendPreviewLog(
                        context = context,
                        source = "章节归档",
                        message = "当前章元信息失败｜type=${current.optString("errorType", "")}｜error=${current.optString("error", "")}"
                    )
                    return@runCatching
                }

                val bookName = current.optString("bookName", "").trim().ifBlank { "默认" }
                writeCurrentBookLightBridge(context, bookName, current)

                val bookKey = current.optString("bookUrl", bookName).md5()
                val chapter = current
                val chapterTitle = chapter.optString("chapterTitle", "")
                val chapterIndex = chapter.optInt("chapterIndex", -1)

                val sentences = splitSentences(chapter.optString("text", ""))
                val index = sentences.indexOfFirst { it.trim() == text.trim() }.takeIf { it >= 0 }
                    ?: nextIndexForPlayback(context, bookKey, chapter)

                saveItem(
                    context = context,
                    bookKey = bookKey,
                    bookName = bookName,
                    chapter = chapter,
                    index = index,
                    text = text.trim(),
                    sampleRate = sampleRate,
                    bytes = bytes,
                    requestPayload = request,
                    status = "partial"
                )

                val chapterKeyForTrim = chapterKey(bookKey, chapterIndex)
                trimPlayedAudioBefore(context, bookKey, chapterKeyForTrim, index)
                trimCurrentBookAudioCache(context, bookKey, chapterKeyForTrim)

                appendPreviewLog(
                    context = context,
                    source = "章节归档",
                    message = "写入成功｜书=$bookName｜章=$chapterIndex $chapterTitle｜句=$index｜text=${text.take(40)}"
                )
            }.onFailure {
                appendPreviewLog(
                    context = context,
                    source = "章节归档",
                    message = "写入失败｜${it.javaClass.simpleName}｜${it.message}"
                )
                logger.warn(it) { "save playback chapter bucket failed" }
            }
        }
    }



    fun warmCurrentWindow(context: Context, liveManager: MixSynthesizer?) {
        if (liveManager == null) return

        // 正在跑整章缓存时，后续句子只查缓存，不重复启动整章分析。
        if (warming.get()) return

        // 同一轮播放中限制触发频率：第一次触发后，两分钟内不再重复触发。
        val now = System.currentTimeMillis()
        if (now - lastWarmRequestAt < WARM_REQUEST_INTERVAL_MS) return
        lastWarmRequestAt = now

        if (!warming.compareAndSet(false, true)) return

        warmJob = scope.launch {
            try {
                cacheWorkMutex.withLock {
                    val manager = getBackgroundManager(context, liveManager)
                    val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
                    appendPreviewLog(
                        context = context,
                        source = "缓存队列",
                        message = "窗口原始返回｜ok=${window.optBoolean("ok", false)}｜bookName=${window.optString("bookName", "")}｜bookUrl=${window.optString("bookUrl", "")}｜chapter=${window.optString("chapterTitle", "")}｜chapters=${window.optJSONArray("chapters")?.length() ?: 0}"
                    )
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
                            chapter = chapterWithNextChapters(chapter, chapters, i)
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


        fun listCurrentReaderPreview(context: Context): List<PreviewBook> {
        return runCatching {
            val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
            if (!window.optBoolean("ok", false)) {
                return emptyList()
            }

            val currentBookName = window.optString("bookName", "")
            val currentBookKey = window
                .optString("bookUrl", currentBookName)
                .md5()

            listPreview(context).filter { it.bookKey == currentBookKey }
        }.onFailure {
            logger.warn(it) { "list current reader preview failed" }
        }.getOrDefault(emptyList())
    }

    fun clearChapter(context: Context, bookKey: String, chapterKey: String): Boolean {
        return chapterDir(context, bookKey, chapterKey).deleteRecursively()
    }

    fun clearAll(context: Context): Boolean {
        return cacheRoot(context).deleteRecursively()
    }

    fun appendPreviewLog(context: Context, source: String, message: String) {
        runCatching {
            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(Date())

            val safeSource = source.replace("|", "/").trim().ifBlank { "缓存工厂" }
            val safeMessage = message
                .replace("\n", " ")
                .replace("\r", " ")
                .trim()

            val line = "$time | $safeSource | $safeMessage\n"

            // 1. 原私有缓存日志：App 内部继续使用
            val file = previewLogFile(context)
            file.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            file.appendText(line, Charsets.UTF_8)
            trimPreviewLogFile(file)

              // 1.1 朗读规则日志同步到底部普通日志系统，供“日志 -> 朗读规则日志”筛选显示
              if (
                  safeSource.contains("朗读规则") ||
                  safeSource.contains("朗读规则运行区")
              ) {
                  logger.info { "[SpeechRule] $safeSource | $safeMessage" }
              }

            // 2. 公开调试日志：给 Termux / 文件管理器直接查看
            runCatching {
                val publicFile = File(
                    "/storage/emulated/0/Android/media/${context.packageName}/jtts-debug/cache_factory.log"
                )
                publicFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                publicFile.appendText(line, Charsets.UTF_8)
                trimPreviewLogFile(publicFile)
            }.onFailure {
                logger.warn(it) { "append public cache factory log failed" }
            }
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
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
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
        if (liveManager == null) {
            onFinished?.invoke(false)
            return
        }

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
        if (liveManager == null) {
            onFinished?.invoke(false)
            return
        }

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


    private fun chapterWithNextChapters(
        chapter: JSONObject,
        chapters: JSONArray,
        currentOffset: Int,
    ): JSONObject {
        val out = JSONObject(chapter.toString())
        val nextArr = JSONArray()

        for (j in currentOffset + 1 until chapters.length()) {
            val next = chapters.optJSONObject(j) ?: continue
            val text = next.optString("chapterText", next.optString("text", ""))
            if (text.isBlank()) continue

            nextArr.put(
                JSONObject()
                    .put("ok", next.optBoolean("ok", true))
                    .put("bookName", next.optString("bookName", ""))
                    .put("bookUrl", next.optString("bookUrl", ""))
                    .put("chapterIndex", next.optInt("chapterIndex", -1))
                    .put("chapterTitle", next.optString("chapterTitle", ""))
                    .put("chapterText", text)
                    .put("text", text)
            )
        }

        out.put("nextChapters", nextArr)
        out.put("nextChapterCount", nextArr.length())
        return out
    }

    private fun joinNextChapterText(nextChapters: JSONArray): String {
        val parts = mutableListOf<String>()

        for (i in 0 until nextChapters.length()) {
            val item = nextChapters.optJSONObject(i) ?: continue
            val title = item.optString("chapterTitle", "")
            val text = item.optString("chapterText", item.optString("text", ""))
            if (text.isBlank()) continue

            parts += buildString {
                if (title.isNotBlank()) {
                    append("【")
                    append(title)
                    append("】\n")
                }
                append(text)
            }
        }

        return parts.joinToString("\n\n")
    }

    private suspend fun cacheChapter(
    context: Context,
    manager: MixSynthesizer,
    bookKey: String,
    bookName: String,
    chapter: JSONObject,
) {
    val realBookName = resolveRoleBookName(bookName, chapter)

    val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
    val dir = chapterDir(context, bookKey, chapterKey)
    val manifest = readManifest(dir) ?: newManifest(realBookName, chapter, chapterKey)
    val configFingerprint = currentConfigFingerprint()

    manifest.put("bookName", realBookName)
    manifest.put("status", "analyzing")
    manifest.put("updatedAt", System.currentTimeMillis())
    writeManifest(dir, manifest)

    // 关键：先把当前真实书名注册/切换到角色管理文件，再让 JS 分析。
    ensureRoleManagerBookContext(context, realBookName)

    val queue = prepareQueue(context, manager, realBookName, chapter).sortedBy { it.index }

    // JS 分析完以后，再把当前角色数据同步到 shuming.<书名>.json / gengxin.json。
    syncRoleManagerBookFiles(context, realBookName, bookKey, chapter, queue)

    if (queue.isEmpty()) {
        manifest.put("status", "failed")
        manifest.put("error", "朗读规则没有生成台词本队列")
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
        return
    }

    writeQueue(dir, queue)
    manifest.put("status", "queue_ready")
    manifest.put("queueSize", queue.size)
    manifest.put("configFingerprint", configFingerprint)
    manifest.put("updatedAt", System.currentTimeMillis())
    writeManifest(dir, manifest)

    manifest.put("status", "caching_audio")
    manifest.put("configFingerprint", configFingerprint)
    manifest.put("updatedAt", System.currentTimeMillis())
    writeManifest(dir, manifest)

    appendPreviewLog(
        context = context,
        source = "缓存队列",
        message = "章节队列就绪｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜${queue.size}句｜按台词本顺序请求"
    )

    for (item in queue) {
        val latestManifest = readManifest(dir) ?: manifest

        // 已存在且参数匹配的缓存，直接标记 ready。
        // 这里不能用 actualItem，因为还没解析 config。
        if (hasItem(latestManifest, item, configFingerprint)) {
            updateQueueItem(dir, item.index, "ready", queueItem = item)
            continue
        }

        appendPreviewLog(
            context = context,
            source = "缓存队列",
            message = "请求音频｜${chapter.optInt("chapterIndex", -1)}#${item.index.toString().padStart(2, '0')}｜${item.raw.optString("roleName", item.tag).ifBlank { "旁白" }}｜${item.voice.ifBlank { "规则未给音色" }}"
        )

        val config = resolveTtsConfig(manager, item)
        if (config == null) {
            updateQueueItem(dir, item.index, "failed", "No matching TTS config", queueItem = item)
            appendPreviewLog(
                context = context,
                source = "缓存队列",
                message = "请求失败｜${chapter.optInt("chapterIndex", -1)}#${item.index.toString().padStart(2, '0')}｜没有匹配音色｜role=${item.raw.optString("roleName", item.tag)}｜voice=${item.voice}"
            )
            continue
        }

        // 关键：把实际使用的 TTS 配置绑定回 QueueItem。
        // 从这里开始，缓存库、queue.json、manifest、实际请求都必须使用 actualItem。
        val actualItem = bindActualRequestItem(item, config)

        updateQueueItem(
            dir = dir,
            index = actualItem.index,
            status = "caching_audio",
            queueItem = actualItem
        )

        appendPreviewLog(
            context = context,
            source = "缓存队列",
            message = "实际请求参数｜${chapter.optInt("chapterIndex", -1)}#${actualItem.index.toString().padStart(2, '0')}｜role=${actualItem.raw.optString("roleName", actualItem.tag)}｜tag=${actualItem.tag}｜voice=${actualItem.voice}"
        )

        val audio = synthesizeQueueItemToPcm(manager, actualItem, config)
        if (audio == null) {
            updateQueueItem(
                dir = dir,
                index = actualItem.index,
                status = "failed",
                error = "TTS request failed",
                queueItem = actualItem
            )
            appendPreviewLog(
                context = context,
                source = "缓存队列",
                message = "请求失败｜${chapter.optInt("chapterIndex", -1)}#${actualItem.index.toString().padStart(2, '0')}｜TTS 请求失败｜voice=${actualItem.voice}"
            )
            continue
        }

        val finalItem = bindCacheSynthResult(actualItem, audio.request)

        saveItem(
            context = context,
            bookKey = bookKey,
            bookName = realBookName,
            chapter = chapter,
            index = actualItem.index,
            text = actualItem.text,
            sampleRate = audio.sampleRate,
            bytes = audio.bytes,
            queueItem = finalItem,
            status = "caching_audio"
        )

        updateQueueItem(
            dir = dir,
            index = finalItem.index,
            status = "ready",
            queueItem = finalItem
        )
    }

    val latest = readManifest(dir) ?: manifest
    val latestQueue = readQueue(dir)
    latest.put(
        "status",
        if (latestQueue.any { it.raw.optString("status") == "failed" }) "failed" else "ready"
    )
    latest.remove("error")
    writeManifest(dir, latest)
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

            val actualItem = bindActualRequestItem(item, config)
            updateQueueItem(dir, actualItem.index, "caching_audio", queueItem = actualItem)

            val audio = synthesizeQueueItemToPcm(manager, actualItem, config)
            if (audio == null) {
                updateQueueItem(dir, item.index, "failed", "TTS request failed")
                return@forEach
            }

            val finalItem = bindCacheSynthResult(actualItem, audio.request)

            val chapter = JSONObject()
                .put("chapterIndex", manifest.optInt("chapterIndex", -1))
                .put("chapterTitle", manifest.optString("chapterTitle", ""))
                .put("textHash", manifest.optString("textHash", ""))

            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = manifest.optString("bookName", ""),
                chapter = chapter,
                index = finalItem.index,
                text = finalItem.text,
                sampleRate = audio.sampleRate,
                bytes = audio.bytes,
                queueItem = finalItem,
                status = "caching_audio"
            )
            updateQueueItem(dir, finalItem.index, "ready", queueItem = finalItem)
        }

        val latest = readManifest(dir) ?: manifest
        latest.put("status", if (readQueue(dir).any { it.raw.optString("status") == "failed" }) "failed" else "ready")
        writeManifest(dir, latest)
        return true
    }

    
private fun bindActualRequestItem(item: QueueItem, config: TtsConfiguration): QueueItem {
    val actualVoice = item.voice.ifBlank { config.source.voice }
    val actualRoleName = item.raw.optString("roleName", "").ifBlank { item.tag }
    val actualTag = item.tag.ifBlank { actualRoleName }.ifBlank { actualVoice }

    val raw = JSONObject(item.raw.toString())
        .put("roleName", actualRoleName)
        .put("tag", actualTag)
        .put("voice", actualVoice)
        .put("actualVoice", actualVoice)
        .put("actualConfigId", config.speechInfo.configId)
        .put("source", rawOptString(item.raw, "source", "speechRule"))

    return item.copy(
        tag = actualTag,
        voice = actualVoice,
        raw = raw
    )
}

private fun rawOptString(raw: JSONObject, key: String, defaultValue: String): String {
    return raw.optString(key, defaultValue).ifBlank { defaultValue }
}


private data class CacheSynthResult(
    val sampleRate: Int,
    val bytes: ByteArray,
    val request: RequestPayload?,
)

private fun bindCacheSynthResult(item: QueueItem, request: RequestPayload?): QueueItem {
    if (request == null) return item

    val raw = JSONObject(item.raw.toString())
    val speechInfo = request.config.speechInfo

    val queueRole = raw.optString("displayRoleName", "")
        .ifBlank { raw.optString("roleName", "") }
        .ifBlank { raw.optString("characterName", "") }
        .ifBlank { raw.optString("speakerName", "") }
        .ifBlank { raw.optString("role", "") }
        .ifBlank { raw.optString("tag", "") }
        .ifBlank { item.tag }
        .trim()

    val requestTag = speechInfo.tag.trim()
    val requestTagName = speechInfo.tagName.trim()
    val requestVoice = runCatching {
        request.config.source.voice.trim()
    }.getOrDefault("")

    val displayRole = when {
        queueRole.isBlank() -> requestTagName.ifBlank { requestTag }.ifBlank { "旁白" }
        queueRole == "narration" -> "旁白"
        else -> queueRole
    }

    val displayVoice = requestVoice.ifBlank { item.voice }

    val characterInfo = JSONObject()
        .put("name", displayRole)
        .put("roleName", displayRole)
        .put("tag", displayRole)
        .put("actualRequestTag", requestTag)
        .put("actualRequestTagName", requestTagName)
        .put("voice", displayVoice)
        .put("actualVoice", displayVoice)

    raw.put("displayRoleName", displayRole)
    raw.put("roleName", displayRole)
    raw.put("role", displayRole)
    raw.put("tag", displayRole)
    raw.put("voice", displayVoice)
    raw.put("actualVoice", displayVoice)
    raw.put("actualRequestTag", requestTag)
    raw.put("actualRequestTagName", requestTagName)
    raw.put("characterInfo", characterInfo)

    return item.copy(
        tag = displayRole,
        voice = displayVoice,
        raw = raw
    )
}

private suspend fun synthesizeQueueItemToPcm(
    manager: MixSynthesizer,
    item: QueueItem,
    config: TtsConfiguration,
): CacheSynthResult? {
    val out = ByteArrayOutputStream()
    var synthesizedSampleRate = config.audioFormat.sampleRate.coerceAtLeast(8000)
    var ok = false
    var requests: List<RequestPayload> = emptyList()

    appendPreviewLog(
        context = manager.context.androidContext,
        source = "缓存队列",
        message = "完整规则合成路线｜index=${item.index}｜tag=${item.tag}｜voice=${item.voice}｜text=${item.text.take(40)}"
    )

    CacheRequestPayloadRecorder.begin()
    try {
        manager.synthesize(
            params = SystemParams(text = item.text),
            forceConfigId = null,
            callback = object : com.github.jing332.tts.synthesizer.SynthesisCallback {
                override fun onSynthesizeStart(sampleRate: Int) {
                    if (sampleRate > 0) synthesizedSampleRate = sampleRate
                }

                override fun onSynthesizeAvailable(audio: ByteArray) {
                    if (audio.isEmpty()) return
                    out.write(audio)
                }
            }
        ).onSuccess {
            ok = true
        }.onFailure { err ->
            logger.warn("full synth queue request failed: ${item.index} | $err")
            appendPreviewLog(
                context = manager.context.androidContext,
                source = "缓存队列",
                message = "完整规则合成失败｜index=${item.index}｜$err"
            )
        }
    } finally {
        requests = CacheRequestPayloadRecorder.end()
    }

    fun normalizeCacheRequestText(value: String): String {
        return value
            .replace(Regex("\\[\\[emo:[^\\]]+\\]\\]"), "")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    val itemRequestKey = normalizeCacheRequestText(item.text)

    val request = requests.lastOrNull {
        normalizeCacheRequestText(it.text) == itemRequestKey
    } ?: requests.lastOrNull {
        val reqKey = normalizeCacheRequestText(it.text)
        val itemShort = itemRequestKey.take(60)
        val reqShort = reqKey.take(60)

        itemShort.isNotBlank() &&
            reqShort.isNotBlank() &&
            (reqKey.contains(itemShort) || itemRequestKey.contains(reqShort))
    }

    if (request != null) {
        appendPreviewLog(
            context = manager.context.androidContext,
            source = "缓存队列",
            message = "完整规则实际请求匹配｜index=${item.index}｜tag=${request.config.speechInfo.tag}｜tagName=${request.config.speechInfo.tagName}｜voice=${request.config.source.voice}｜text=${request.text.take(40)}"
        )
    } else {
        appendPreviewLog(
            context = manager.context.androidContext,
            source = "缓存队列",
            message = "完整规则实际请求未匹配｜index=${item.index}｜routeTag=${item.tag}｜routeVoice=${item.voice}｜text=${item.text.take(40)}"
        )
    }

    val bytes = out.toByteArray()
    return if (ok && bytes.isNotEmpty()) {
        CacheSynthResult(
            sampleRate = synthesizedSampleRate,
            bytes = bytes,
            request = request
        )
    } else {
        null
    }
}

    private suspend fun getBackgroundManager(
        context: Context,
        liveManager: MixSynthesizer,
    ): MixSynthesizer {
        val current = backgroundManager
        if (current != null && current.isInitialized) return current

        val cfg = liveManager.context.cfg.copy(
            bgmEnabled = { false },
            streamPlayEnabled = SysTtsConfig::isStreamPlayModeEnabled,
            audioParams = {
                AudioParams(
                    speed = SysTtsConfig.audioParamsSpeed,
                    volume = SysTtsConfig.audioParamsVolume,
                    pitch = SysTtsConfig.audioParamsPitch
                )
            }
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

    private fun prepareQueue(
    context: Context,
    manager: MixSynthesizer,
    bookName: String,
    chapter: JSONObject,
): List<QueueItem> {
    val rule = findActiveSpeechRule()
    val chapterText = chapter.optString("text", "")
    val nextChapters = chapter.optJSONArray("nextChapters") ?: JSONArray()
    val nextChapterText = joinNextChapterText(nextChapters)
    val fullAnalyzeText = buildString {
        append(chapterText)
        if (nextChapterText.isNotBlank()) {
            append("\n\n【后续章节】\n")
            append(nextChapterText)
        }
    }

    if (chapterText.isBlank()) return emptyList()

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
                        "text" to chapterText,
                            "nextChapters" to nextChapters,
                            "nextChaptersJson" to nextChapters.toString(),
                            "nextChapterText" to nextChapterText,
                            "fullAnalyzeText" to fullAnalyzeText,
                            "cacheMode" to true
                    )
                )
        }.onFailure {
            logger.warn(it) { "prepareChapterAudioQueue failed" }
            appendPreviewLog(
                context = context,
                source = "朗读规则运行区",
                message = "prepareChapterAudioQueue失败｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜${it.message.orEmpty()}"
            )
        }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

      appendPreviewLog(
        context = context,
        source = "朗读规则运行区",
        message = "朗读规则返回audioQueue｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜${rawQueue.size}句"
    )

    rawQueue.take(5).forEachIndexed { i, raw ->
        appendPreviewLog(
            context = context,
            source = "朗读规则运行区",
            message = "audioQueue样本#$i｜${raw.toString().take(500)}"
        )
    }

    val queue = rawQueue.mapIndexedNotNull { fallbackIndex, raw ->
        val text = raw.firstString("text", "content", "line", "sentence", "value", "台词", "内容")
        if (text.isBlank()) return@mapIndexedNotNull null

        val index = raw["index"]?.toString()?.toIntOrNull() ?: fallbackIndex
          fun nestedFirstString(value: Any?, vararg keys: String): String {
              if (value == null) return ""

              fun fromJsonObject(obj: JSONObject): String {
                  for (key in keys) {
                      val v = obj.optString(key, "").trim()
                      if (v.isNotBlank()) return v
                  }
                  return ""
              }

              return when (value) {
                  is JSONObject -> fromJsonObject(value)
                  is Map<*, *> -> {
                      for (key in keys) {
                          val v = value[key]?.toString()?.trim().orEmpty()
                          if (v.isNotBlank()) return v
                      }
                      ""
                  }
                  else -> runCatching {
                      val s = value.toString().trim()
                      if (s.startsWith("{") && s.endsWith("}")) {
                          fromJsonObject(JSONObject(s))
                      } else {
                          ""
                      }
                  }.getOrDefault("")
              }
          }

          val characterInfo = raw["characterInfo"]
              ?: raw["roleInfo"]
              ?: raw["speakerInfo"]

          val characterInfoRoleName = nestedFirstString(
              characterInfo,
              "name",
              "roleName",
              "role",
              "characterName",
              "speakerName",
              "speaker",
              "人物",
              "角色",
              "说话人"
          )

          val characterInfoVoice = nestedFirstString(
              characterInfo,
              "voice",
              "voiceName",
              "voiceId",
              "speakerVoice",
              "tts",
              "ttsName",
              "音色",
              "声音"
          )

          val roleName = raw.firstString(
              "roleName",
              "role",
              "characterName",
              "character",
              "speakerName",
              "speaker",
              "人物",
              "角色",
              "说话人"
          ).ifBlank { characterInfoRoleName }

          val voice = raw.firstString(
              "voice",
              "voiceName",
              "voiceId",
              "speakerVoice",
              "tts",
              "ttsName",
              "音色",
              "声音"
          ).ifBlank { characterInfoVoice }

          val actualVoice = raw.firstString(
              "actualVoice",
              "finalVoice",
              "resolvedVoice",
              "实际音色"
          ).ifBlank { voice }

          val rawTag = raw.firstString("tag", "speechTag", "标签")
          val tag = roleName
              .ifBlank { rawTag }
              .ifBlank { voice }

        val emotion = raw.firstString("emotion", "emo", "style", "mood", "情绪", "感情")
        val speed = raw.firstFloat(1f, "speed", "rate", "语速")
        val pitch = raw.firstFloat(1f, "pitch", "tone", "音高")
        val volume = raw.firstFloat(1f, "volume", "vol", "音量")
        val source = raw.firstString("source").ifBlank { "speechRule" }

        val normalizedRaw = JSONObject(raw)
            .put("index", index)
            .put("text", text)
            .put("roleName", roleName)
            .put("tag", tag)
            .put("voice", voice)
            .put("actualVoice", actualVoice)
            .put("characterInfoRoleName", characterInfoRoleName)
            .put("characterInfoVoice", characterInfoVoice)
            .put("rawTag", rawTag)
            .put("emotion", emotion)
            .put("speed", speed)
            .put("pitch", pitch)
            .put("volume", volume)
            .put("source", source)
            .put("status", raw.firstString("status", "state").ifBlank { "pending" })

        QueueItem(
            index = index,
            text = text,
            tag = tag,
            voice = actualVoice.ifBlank { voice },
            emotion = emotion,
            speed = speed,
            pitch = pitch,
            volume = volume,
            raw = normalizedRaw
        )
    }.sortedBy { it.index }

    if (queue.isNotEmpty()) return queue

    appendPreviewLog(
        context = context,
        source = "朗读规则运行区",
        message = "朗读规则未生成有效audioQueue｜${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜已禁止旧handleText兜底，避免生成错误缓存"
    )

    return emptyList()
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
          queueItem: QueueItem? = null,
          requestPayload: RequestPayload? = null,
          status: String,
      ) {
          val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
          val dir = chapterDir(context, bookKey, chapterKey)
          if (!dir.exists()) dir.mkdirs()

          val configFingerprint = currentConfigFingerprint()
          val audioFile = File(dir, "${index}_${text.md5()}_${configFingerprint.take(12)}.pcm")
          audioFile.writeBytes(bytes)
          audioFile.setLastModified(System.currentTimeMillis())
          // 手动清理模式：写入音频后不自动清理

          val requestTag = requestPayload?.config?.speechInfo?.tag?.trim().orEmpty()
          val requestTagName = requestPayload?.config?.speechInfo?.tagName?.trim().orEmpty()
          val requestVoice = runCatching {
              requestPayload?.config?.source?.voice?.trim().orEmpty()
          }.getOrDefault("")

          val roleFromQueue = queueItem?.raw?.optString("roleName", "").orEmpty()
          val displayRoleRaw = roleFromQueue
              .ifBlank { queueItem?.raw?.optString("displayRoleName", "").orEmpty() }
              .ifBlank { requestTagName }
              .ifBlank { requestTag }
              .ifBlank { queueItem?.tag.orEmpty() }
              .ifBlank { "旁白" }

          val displayRole = when (displayRoleRaw) {
              "narration" -> "旁白"
              else -> displayRoleRaw
          }

          val displayTag = queueItem?.tag.orEmpty()
              .ifBlank { requestTag }
              .ifBlank { displayRole }

          val displayVoice = queueItem?.voice.orEmpty()
              .ifBlank { requestVoice }

          val displayEmotion = queueItem?.emotion.orEmpty()

          val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
          val items = manifest.optJSONArray("items") ?: JSONArray().also { manifest.put("items", it) }

          upsertItem(
              items = items,
              item = JSONObject()
                  .put("index", index)
                  .put("text", text)
                  .put("textHash", text.md5())
                  .put("lookupTextHash", text.lookupTextKey().md5())
                  .also { putPointerFieldsForAudioCache(context, text, it) }
                  .put("roleName", displayRole)
                  .put("displayRoleName", displayRole)
                  .put("tag", displayTag)
                  .put("tagName", requestTagName)
                  .put("actualRequestTag", requestTag)
                  .put("actualRequestTagName", requestTagName)
                  .put("voice", displayVoice)
                  .put("actualVoice", displayVoice)
                  .put("actualConfigId", queueItem?.raw?.optLong("actualConfigId", 0L) ?: 0L)
                  .put("emotion", displayEmotion)
                  .put("speed", queueItem?.speed ?: 1f)
                  .put("pitch", queueItem?.pitch ?: 1f)
                  .put("volume", queueItem?.volume ?: 1f)
                  .put("source", queueItem?.raw?.optString("source", "speechRule").orEmpty().ifBlank { "speechRule" })
                  .put("configFingerprint", configFingerprint)
                  .put("sampleRate", sampleRate.coerceAtLeast(8000))
                  .put("path", audioFile.absolutePath)
                  .put("status", "ready")
                  .put("updatedAt", System.currentTimeMillis())
          )

          val readyCount = items.length()
          manifest.put("bookName", bookName)
          manifest.put("configFingerprint", configFingerprint)
          manifest.put("readyCount", readyCount)
          manifest.put("failedCount", 0)
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

    private fun updateQueueItem(
    dir: File,
    index: Int,
    status: String,
    error: String = "",
    queueItem: QueueItem? = null,
) {
    val file = File(dir, "queue.json")
    if (!file.exists()) return

    val arr = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        if (item.optInt("index", -1) != index) continue

        if (queueItem != null) {
            item.put("text", queueItem.text)
            item.put("roleName", queueItem.raw.optString("roleName", ""))
            item.put("tag", queueItem.tag)
            item.put("voice", queueItem.voice)
            item.put("emotion", queueItem.emotion)
            item.put("speed", queueItem.speed)
            item.put("pitch", queueItem.pitch)
            item.put("volume", queueItem.volume)
            item.put("source", queueItem.raw.optString("source", "speechRule"))
            item.put("actualVoice", queueItem.raw.optString("actualVoice", queueItem.voice))
            item.put("actualConfigId", queueItem.raw.optLong("actualConfigId", 0L))
        }

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
            return file.exists() && file.length() > 0
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
    // 角色管理插件用 ttsrv.readTxtFile("liebiao.json") 这种根文件名方式读取。
    // 所以主目录使用 externalFilesDir 根目录。
    return context.getExternalFilesDir(null) ?: context.filesDir
}

private fun roleManagerDirs(context: Context): List<File> {
    val root = context.getExternalFilesDir(null) ?: context.filesDir
    val pluginDir = File(root, "plugins/mingwuyan")

    // 同时写根目录和旧 plugins/mingwuyan 目录，避免历史版本/插件读取路径不一致。
    return listOf(root, pluginDir).distinctBy { it.absolutePath }
}


    
    private fun resolveRoleBookName(bookName: String, chapter: JSONObject): String {
    val directBook = bookName.trim()
    val chapterBook = chapter.optString("bookName", "").trim()
    val chapterTitleBook = chapter.optString("bookTitle", "").trim()

    // 当前书唯一来源优先级：
    // 1. 本次章节对象携带的 bookName/bookTitle
    // 2. 当前窗口传入的 bookName
    // 3. 默认
    return chapterBook
        .ifBlank { chapterTitleBook }
        .ifBlank { directBook }
        .ifBlank { "默认" }
}




    private fun writeRoleManagerCurrentBookBridge(context: Context, bookName: String) {
        // 回退逐句模式：TTS 端不再反向写角色管理当前书
        return

        val safeBookName = bookName.trim()
        if (safeBookName.isBlank()) return

        val meta = org.json.JSONObject()
            .put("bookName", safeBookName)
            .put("book", safeBookName)
            .put("bookTitle", safeBookName)
            .put("title", safeBookName)
            .put("source", "AudioCacheFactory.roleManagerPreSwitch")
            .put("updatedAt", System.currentTimeMillis())
            .toString()

        val dirs = mutableListOf<java.io.File>()
        // 公开桥接目录：角色管理插件优先读取这里
        dirs += java.io.File("/storage/emulated/0/TTSvoices/role_manager_shared")
        dirs += java.io.File("/sdcard/TTSvoices/role_manager_shared")
        dirs += context.filesDir
        dirs += java.io.File(context.filesDir, "role_manager_shared")
        context.getExternalFilesDir(null)?.let {
            dirs += it
            dirs += java.io.File(it, "role_manager_shared")
        }

        dirs.distinctBy { it.absolutePath }.forEach { dir ->
            runCatching {
                dir.mkdirs()
                java.io.File(dir, "cunfang.txt").writeText(safeBookName)
                java.io.File(dir, "cache_book_context_meta.json").writeText(meta)
            }
        }
    }


    private fun safeRoleBookName(bookName: String): String {
    return bookName
        .trim()
        .ifBlank { "默认" }
        .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
        .replace(Regex("""^\.+"""), "")
        .ifBlank { "默认" }
}



@Suppress("UNUSED_PARAMETER")
private fun ensureRoleManagerBookContext(
    context: Context,
    bookName: String,
) {
        // 回退逐句模式：不再由 APK/TTS 自动预切角色管理当前书
        return

    runCatching {
        val safeBookName = safeRoleBookName(bookName)
        val dirs = roleManagerDirs(context)

        dirs.forEach { dir ->
            if (!dir.exists()) dir.mkdirs()

            val cunfangFile = File(dir, "cunfang.txt")
            val characterFile = File(dir, "characterRecords.json")
            val gengxinFile = File(dir, "gengxin.json")
            val listFile = File(dir, "liebiao.json")

            val oldBookName = runCatching {
                if (cunfangFile.exists()) {
                    cunfangFile.readText(Charsets.UTF_8).trim().ifBlank { "默认" }
                } else {
                    "默认"
                }
            }.getOrDefault("默认")

            val currentRecords = readRoleRecords(characterFile)

            if (oldBookName != safeBookName) {
                // 保存旧书当前角色，避免切书时丢失
                if (oldBookName.isNotBlank()) {
                    File(dir, "shuming.$oldBookName.json")
                        .writeText(currentRecords.toString(2), Charsets.UTF_8)
                }

                // 加载目标书角色；目标书不存在时创建空角色表
                val targetBookFile = File(dir, "shuming.$safeBookName.json")
                if (!targetBookFile.exists()) {
                    targetBookFile.writeText("[]", Charsets.UTF_8)
                }

                val targetRecords = readRoleRecords(targetBookFile)
                val targetText = targetRecords.toString(2)

                characterFile.writeText(targetText, Charsets.UTF_8)
                gengxinFile.writeText(targetText, Charsets.UTF_8)
            } else {
                // 同一本书，确保 shuming 文件存在
                val targetBookFile = File(dir, "shuming.$safeBookName.json")
                if (!targetBookFile.exists()) {
                    targetBookFile.writeText(currentRecords.toString(2), Charsets.UTF_8)
                }
            }

            // 当前书名
            cunfangFile.writeText(safeBookName, Charsets.UTF_8)

            // 书籍列表：当前书放第一位，避免角色管理插件默认停在“默认”
            val names = linkedSetOf<String>()
            names += safeBookName

            runCatching {
                if (listFile.exists()) {
                    val arr = JSONArray(listFile.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) names += n
                    }
                }
            }

            names += "默认"

            val out = JSONArray()
            names.forEach { out.put(it) }
            listFile.writeText(out.toString(2), Charsets.UTF_8)

            File(dir, "cache_book_context_meta.json").writeText(
                JSONObject()
                    .put("bookName", safeBookName)
                    .put("oldBookName", oldBookName)
                    .put("reason", "apk_pre_switch_book")
                    .put("updatedAt", System.currentTimeMillis())
                    .toString(2),
                Charsets.UTF_8
            )
        }

        // 暂停：safeBookName 可能来自旧窗口，不能反写当前书桥接
        appendPreviewLog(
            context = context,
            source = "角色管理预切书",
            message = "当前书桥接暂停写入=$safeBookName"
        )

        appendPreviewLog(
            context = context,
            source = "角色管理预切书",
            message = "已切换角色管理当前书=$safeBookName｜目录数=${dirs.size}"
        )
    }.onFailure {
        logger.warn(it) { "ensure role manager book context failed" }
        appendPreviewLog(
            context = context,
            source = "角色管理预切书",
            message = "切书失败：${it.message.orEmpty()}"
        )
    }
}





@Suppress("UNUSED_PARAMETER")
private fun syncRoleManagerBookFiles(
    context: Context,
    bookName: String,
    bookKey: String,
    chapter: JSONObject,
    queue: List<QueueItem>,
) {
    // 关键修复：
    // 不再根据 queue 反向生成角色表。
    // queue 里的 tag / voice 是音色标签，不是实名角色。
    // 角色实名、别名、发音人分配，由朗读规则 JS 的 CharacterManager.saveRecords() 写入。
    appendPreviewLog(
        context = context,
        source = "角色管理",
        message = "跳过APK角色表同步｜交由朗读规则/角色管理插件维护｜书=$bookName｜章节=${chapter.optString("chapterTitle", "")}｜队列=${queue.size}"
    )
}







    private fun readRoleRecords(file: File): JSONArray {
    return runCatching {
        if (!file.exists()) return JSONArray()
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.startsWith("[")) JSONArray(text) else JSONArray()
    }.getOrDefault(JSONArray())
}

private fun readRoleRecordsFromDirs(context: Context, fileName: String): JSONArray {
    var firstEmpty: JSONArray? = null

    for (dir in roleManagerDirs(context)) {
        val file = File(dir, fileName)
        if (!file.exists()) continue

        val arr = readRoleRecords(file)
        if (arr.length() > 0) return arr
        if (firstEmpty == null) firstEmpty = arr
    }

    return firstEmpty ?: JSONArray()
}

private fun readRoleTextFromDirs(
    context: Context,
    fileName: String,
    defaultValue: String,
): String {
    for (dir in roleManagerDirs(context)) {
        val file = File(dir, fileName)
        if (!file.exists()) continue

        val text = runCatching {
            file.readText(Charsets.UTF_8).trim()
        }.getOrDefault("")

        if (text.isNotBlank()) return text
    }

    return defaultValue
}

private fun writeRoleTextToDirs(
    dirs: List<File>,
    fileName: String,
    content: String,
) {
    dirs.forEach { dir ->
        if (!dir.exists()) dir.mkdirs()
        File(dir, fileName).writeText(content, Charsets.UTF_8)
    }
}



    private fun writeRoleBookList(dir: File, bookName: String) {
    if (!dir.exists()) dir.mkdirs()

    val realBookName = bookName.trim().ifBlank { "默认" }
    val file = File(dir, "liebiao.json")

    val names = linkedSetOf<String>()
    names += "默认"

    runCatching {
        if (file.exists()) {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val name = when (item) {
                    is JSONObject -> item.optString("bookName", "")
                        .ifBlank { item.optString("name", "") }
                        .ifBlank { item.optString("title", "") }

                    else -> arr.optString(i, "")
                }.trim()

                if (name.isNotBlank()) names += name
            }
        }
    }

    names += realBookName

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
        .mapNotNull { item ->
            val roleName = item.raw.optString("roleName", "")
                .ifBlank { item.raw.optString("role", "") }
                .ifBlank { item.raw.optString("characterName", "") }
                .ifBlank { item.raw.optString("character", "") }
                .ifBlank { item.raw.optString("speakerName", "") }
                .ifBlank { item.raw.optString("speaker", "") }
                .ifBlank { item.raw.optString("人物", "") }
                .ifBlank { item.raw.optString("角色", "") }
                .ifBlank { item.raw.optString("说话人", "") }
                .ifBlank { item.tag }
                .trim()

            if (roleName.isBlank()) return@mapNotNull null

            val lower = roleName.lowercase(Locale.getDefault())
            if (
                roleName == "旁白" ||
                roleName == "未知" ||
                roleName == "未知发言人" ||
                lower == "narration" ||
                lower == "default" ||
                lower == "duihua" ||
                lower == "duihuaa" ||
                lower == "duihuab"
            ) {
                return@mapNotNull null
            }

            roleName to item
        }
        .groupBy({ it.first }, { it.second })
        .forEach { (name, items) ->
            val old = byName[name] ?: JSONObject().put("name", name)

            val voice = items.firstNotNullOfOrNull { item ->
                item.voice.takeIf { it.isNotBlank() }
                    ?: item.raw.optString("voice", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("voiceName", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("voiceId", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("speakerVoice", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("tts", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("ttsName", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("音色", "").takeIf { it.isNotBlank() }
                    ?: item.raw.optString("声音", "").takeIf { it.isNotBlank() }
                    ?: item.tag.takeIf { it.isNotBlank() && it != name }
            } ?: old.optString("voice", "")

            old.put("name", name)
            normalizeRoleRecordForPlugin(old)

            if (!old.has("aliases")) old.put("aliases", name)
            if (voice.isNotBlank()) old.put("voice", voice)
            if (!old.has("gender")) old.put("gender", "")
            if (!old.has("genderAge")) old.put("genderAge", old.optString("age", ""))
            if (!old.has("age")) old.put("age", old.optString("genderAge", ""))
            if (!old.has("genderAgeHistory")) old.put("genderAgeHistory", JSONArray())

            old.put("usageCount", old.optInt("usageCount", 0) + items.size)

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

    /**
     * 清理当前书当前章节的 PCM 音频缓存。
     *
     * 只删除 .pcm 音频文件，不删除 manifest.json / queue.json / 当前书信息。
     * 后续开源阅读端朗读完成或整章音频合成完成后，可以调用这个函数。
     */
    fun clearCurrentChapterPcmCache(context: Context): Int {
        return runCatching {
            val current = ReaderChapterBridgeClient.fetchCurrentChapterJson()
            if (!current.optBoolean("ok", false)) return 0

            val bookName = current.optString("bookName", "").trim().ifBlank { "默认" }
            val bookKey = current.optString("bookUrl", bookName).md5()
            val chapterKey = chapterKey(bookKey, current.optInt("chapterIndex", -1))
            val dir = chapterDir(context, bookKey, chapterKey)

            val deletedCount = clearPcmFilesOnly(dir)

            appendPreviewLog(
                context = context,
                source = "PCM缓存清理",
                message = "已清理当前章节PCM｜书=$bookName｜章=${current.optInt("chapterIndex", -1)} ${current.optString("chapterTitle", "")}｜删除=${deletedCount}个"
            )

            deletedCount
        }.getOrElse {
            logger.warn(it) { "clear current chapter pcm cache failed" }
            0
        }
    }

    /**
     * 只删除目录下的 .pcm 文件，不动 json / meta / queue。
     */
    private fun clearPcmFilesOnly(dir: File): Int {
        if (!dir.exists()) return 0

        var count = 0
        dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("pcm", ignoreCase = true) }
            .forEach { file ->
                if (file.delete()) count++
            }

        return count
    }

    /**
     * 自动限额清理 PCM 音频缓存。
     *
     * 默认最多保留 80MB。
     * 超过后删除最旧的 .pcm，清到 64MB 左右。
     * 最多每 60 秒检查一次，避免频繁扫描导致卡顿。
     */
    private fun trimPcmAudioCacheIfNeeded(
        context: Context,
        maxBytes: Long = 80L * 1024L * 1024L,
        minIntervalMs: Long = 60_000L
    ) {
        runCatching {
            val root = cacheRoot(context)
            if (!root.exists()) return

            val marker = File(root, ".last_pcm_trim")
            val now = System.currentTimeMillis()

            if (marker.exists() && now - marker.lastModified() < minIntervalMs) {
                return
            }

            marker.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            marker.writeText(now.toString(), Charsets.UTF_8)

            val pcmFiles = root
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("pcm", ignoreCase = true) }
                .toList()

            var totalBytes = pcmFiles.sumOf { it.length() }
            if (totalBytes <= maxBytes) return

            val targetBytes = (maxBytes * 0.8).toLong()
            var deletedCount = 0

            pcmFiles
                .sortedBy { it.lastModified() }
                .forEach { file ->
                    if (totalBytes <= targetBytes) return@forEach

                    val len = file.length()
                    if (file.delete()) {
                        totalBytes -= len
                        deletedCount++
                    }
                }

            if (deletedCount > 0) {
                appendPreviewLog(
                    context = context,
                    source = "PCM缓存清理",
                    message = "自动清理PCM缓存｜删除=${deletedCount}个｜剩余=${totalBytes / 1024 / 1024}MB｜上限=${maxBytes / 1024 / 1024}MB"
                )
            }
        }.onFailure {
            logger.warn(it) { "trim pcm audio cache failed" }
        }
    }

    data class AudioCacheStat(
        val pcmBytes: Long,
        val pcmCount: Int,
        val mp3Bytes: Long,
        val mp3Count: Int
    )

    data class AudioChapterCacheInfo(
        val bookKey: String,
        val bookName: String,
        val chapterKey: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val pcmBytes: Long,
        val pcmCount: Int,
        val mp3Bytes: Long,
        val mp3Count: Int
    )

    fun getAudioCacheStat(context: Context): AudioCacheStat {
        return runCatching {
            val root = cacheRoot(context)
            if (!root.exists()) {
                return AudioCacheStat(0L, 0, 0L, 0)
            }

            var pcmBytes = 0L
            var pcmCount = 0
            var mp3Bytes = 0L
            var mp3Count = 0

            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    when (file.extension.lowercase()) {
                        "pcm" -> {
                            pcmBytes += file.length()
                            pcmCount++
                        }
                        "mp3" -> {
                            mp3Bytes += file.length()
                            mp3Count++
                        }
                    }
                }

            AudioCacheStat(
                pcmBytes = pcmBytes,
                pcmCount = pcmCount,
                mp3Bytes = mp3Bytes,
                mp3Count = mp3Count
            )
        }.getOrDefault(AudioCacheStat(0L, 0, 0L, 0))
    }

    fun formatAudioCacheSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"

        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.1f KB".format(kb)
            else -> "$bytes B"
        }
    }

    fun listAudioChapterCaches(context: Context): List<AudioChapterCacheInfo> {
        return runCatching {
            val root = cacheRoot(context)
            if (!root.exists()) return emptyList()

            val result = mutableListOf<AudioChapterCacheInfo>()

            root.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { bookDir ->
                    bookDir.listFiles()
                        ?.filter { it.isDirectory }
                        ?.forEach { chapterDir ->
                            val audioFiles = chapterDir
                                .walkTopDown()
                                .filter { it.isFile && (it.extension.equals("pcm", true) || it.extension.equals("mp3", true)) }
                                .toList()

                            if (audioFiles.isEmpty()) return@forEach

                            val manifest = readManifest(chapterDir)

                            val pcmFiles = audioFiles.filter { it.extension.equals("pcm", true) }
                            val mp3Files = audioFiles.filter { it.extension.equals("mp3", true) }

                            result += AudioChapterCacheInfo(
                                bookKey = bookDir.name,
                                bookName = manifest?.optString("bookName", "").orEmpty().ifBlank { bookDir.name },
                                chapterKey = chapterDir.name,
                                chapterIndex = manifest?.optInt("chapterIndex", -1) ?: -1,
                                chapterTitle = manifest?.optString("chapterTitle", "").orEmpty(),
                                pcmBytes = pcmFiles.sumOf { it.length() },
                                pcmCount = pcmFiles.size,
                                mp3Bytes = mp3Files.sumOf { it.length() },
                                mp3Count = mp3Files.size
                            )
                        }
                }

            result.sortedWith(
                compareBy<AudioChapterCacheInfo> { it.bookName }
                    .thenBy { it.chapterIndex }
                    .thenBy { it.chapterTitle }
            )
        }.getOrDefault(emptyList())
    }

    private fun clearAudioFilesOnly(dir: File, extensions: Set<String>): Int {
        if (!dir.exists()) return 0

        var count = 0
        dir.walkTopDown()
            .filter { file ->
                file.isFile && extensions.any { ext -> file.extension.equals(ext, ignoreCase = true) }
            }
            .forEach { file ->
                if (file.delete()) count++
            }

        return count
    }

    fun clearAudioCacheByExtensions(context: Context, extensions: Set<String>): Int {
        return runCatching {
            val deletedCount = clearAudioFilesOnly(cacheRoot(context), extensions)

            appendPreviewLog(
                context = context,
                source = "音频缓存清理",
                message = "一键清理音频缓存｜类型=${extensions.joinToString(",")}｜删除=${deletedCount}个"
            )

            deletedCount
        }.getOrElse {
            logger.warn(it) { "clear audio cache failed" }
            0
        }
    }

    fun clearAudioBookCacheByExtensions(context: Context, bookKey: String, extensions: Set<String>): Int {
        return runCatching {
            val dir = File(cacheRoot(context), bookKey)
            val deletedCount = clearAudioFilesOnly(dir, extensions)

            appendPreviewLog(
                context = context,
                source = "音频缓存清理",
                message = "按书清理音频缓存｜bookKey=$bookKey｜类型=${extensions.joinToString(",")}｜删除=${deletedCount}个"
            )

            deletedCount
        }.getOrElse {
            logger.warn(it) { "clear book audio cache failed" }
            0
        }
    }

    fun clearAudioChapterCacheByExtensions(
        context: Context,
        bookKey: String,
        chapterKey: String,
        extensions: Set<String>
    ): Int {
        return runCatching {
            val dir = chapterDir(context, bookKey, chapterKey)
            val deletedCount = clearAudioFilesOnly(dir, extensions)

            appendPreviewLog(
                context = context,
                source = "音频缓存清理",
                message = "按章节清理音频缓存｜bookKey=$bookKey｜chapterKey=$chapterKey｜类型=${extensions.joinToString(",")}｜删除=${deletedCount}个"
            )

            deletedCount
        }.getOrElse {
            logger.warn(it) { "clear chapter audio cache failed" }
            0
        }
    }

    fun clearAllPcmAudioCache(context: Context): Int {
        return clearAudioCacheByExtensions(context, setOf("pcm"))
    }

    fun clearAllMp3AudioCache(context: Context): Int {
        return clearAudioCacheByExtensions(context, setOf("mp3"))
    }

    private fun cacheRoot(context: Context): File {
        return context.getExternalFilesDir("reader_audio_cache")
            ?: File(context.filesDir, "reader_audio_cache")
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

    private fun previewChapter(bookKey: String, dir: File): PreviewChapter? {
        val manifest = readManifest(dir)
        val queue = readQueue(dir)
        if (manifest == null && queue.isEmpty()) return null
        fun displayRole(rawRole: String, fallback: String): String {
            val value = rawRole.ifBlank { fallback }.trim()
            return when {
                value.equals("duihua", ignoreCase = true) -> "对话角色（完整规则）"
                value.equals("narration", ignoreCase = true) -> "旁白"
                value.isBlank() -> "旁白"
                else -> value
            }
        }

        fun displayVoice(rawVoice: String): String {
            val value = rawVoice.trim()
            return when {
                value.isBlank() -> "完整规则合成"
                value.equals("tts.default.placeholder", ignoreCase = true) -> "完整规则合成"
                value.equals("默认音色", ignoreCase = true) -> "完整规则合成"
                else -> value
            }
        }


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
                    tag = displayRole(
                        cached?.optString("roleName", "").orEmpty()
                            .ifBlank { item.raw.optString("roleName", "") },
                        cached?.optString("tag", "").orEmpty()
                            .ifBlank { item.tag }
                    ),
                    voice = displayVoice(
                        cached?.optString("actualVoice", "").orEmpty()
                            .ifBlank { item.raw.optString("actualVoice", "") }
                            .ifBlank { cached?.optString("voice", "").orEmpty() }
                            .ifBlank { item.voice }
                    ),
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
                    tag = displayRole(
                        item.optString("roleName", ""),
                        item.optString("tag", "")
                    ),
                    voice = displayVoice(
                        item.optString("actualVoice", "")
                            .ifBlank { item.optString("voice", "") }
                    ),
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

    /**
     * Bridge 导出：把 AudioCacheFactory 现有的播放缓存片段复制到
     * externalFiles/reader_audio_cache/exports/<requestId>/ 下，
     * 并返回带 content:// audioUri 的 manifest。
     *
     * 注意：这里不合成音频，只导出现有缓存。
     */

    private fun bridgeExportAudioMimeType(file: File, item: JSONObject): String {
        val name = file.name.lowercase(Locale.ROOT)
        val extMime = when {
            name.endsWith(".pcm") -> "audio/pcm"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".m4a") -> "audio/mp4"
            name.endsWith(".aac") -> "audio/aac"
            else -> ""
        }
        if (extMime.isNotBlank()) return extMime

        val itemMime = item.optString("audioMimeType", "")
            .ifBlank { item.optString("mimeType", "") }
            .ifBlank { item.optString("contentType", "") }

        return itemMime.ifBlank { "application/octet-stream" }
    }

    private fun bridgeExportSampleRate(item: JSONObject): Int {
        val sr = item.optInt(
            "sampleRate",
            item.optInt("sample_rate", item.optInt("samplingRate", 16000))
        )
        return sr.coerceAtLeast(8000)
    }

    private fun bridgeExportChannelCount(item: JSONObject): Int {
        val ch = item.optInt(
            "channels",
            item.optInt("channelCount", item.optInt("channel_count", 1))
        )
        return ch.coerceAtLeast(1)
    }

    private fun bridgeExportBitsPerSample(item: JSONObject): Int {
        val bits = item.optInt(
            "bitsPerSample",
            item.optInt("bits_per_sample", item.optInt("bitDepth", 16))
        )
        return bits.coerceAtLeast(8)
    }



    private fun bridgeExportIsRawPcm(src: File, item: JSONObject): Boolean {
        val name = src.name.lowercase(Locale.ROOT)
        val mime = bridgeExportAudioMimeType(src, item)
        return name.endsWith(".pcm") || mime.equals("audio/pcm", true)
    }

    private fun bridgeExportTargetExtension(src: File, item: JSONObject): String {
        val name = src.name.lowercase(Locale.ROOT)
        return when {
            bridgeExportIsRawPcm(src, item) -> "wav"
            name.endsWith(".wav") -> "wav"
            name.endsWith(".mp3") -> "mp3"
            name.endsWith(".m4a") -> "m4a"
            name.endsWith(".aac") -> "aac"
            bridgeExportAudioMimeType(src, item).contains("wav", true) -> "wav"
            bridgeExportAudioMimeType(src, item).contains("mpeg", true) -> "mp3"
            bridgeExportAudioMimeType(src, item).contains("mp4", true) -> "m4a"
            bridgeExportAudioMimeType(src, item).contains("aac", true) -> "aac"
            else -> src.extension.ifBlank { "bin" }.lowercase(Locale.ROOT)
        }
    }

    private fun bridgeExportedAudioMimeType(src: File, item: JSONObject): String {
        return if (bridgeExportIsRawPcm(src, item)) {
            "audio/wav"
        } else {
            bridgeExportAudioMimeType(src, item)
        }
    }

    private fun writeBridgeAscii(out: java.io.OutputStream, value: String) {
        out.write(value.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
    }

    private fun writeBridgeLeShort(out: java.io.OutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun writeBridgeLeInt(out: java.io.OutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
    }

    private fun writeBridgeRawPcmAsWav(src: File, dst: File, item: JSONObject) {
        val sampleRate = bridgeExportSampleRate(item)
        val channels = bridgeExportChannelCount(item)
        val bitsPerSample = bridgeExportBitsPerSample(item)
        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        val dataSizeLong = src.length()

        if (dataSizeLong <= 0L) {
            throw IllegalStateException("PCM 文件为空: ${src.absolutePath}")
        }
        if (dataSizeLong > Int.MAX_VALUE - 44L) {
            throw IllegalStateException("PCM 文件过大，无法写 WAV header: ${src.absolutePath}")
        }

        val dataSize = dataSizeLong.toInt()
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample

        dst.parentFile?.mkdirs()

        java.io.FileOutputStream(dst).use { out ->
            writeBridgeAscii(out, "RIFF")
            writeBridgeLeInt(out, 36 + dataSize)
            writeBridgeAscii(out, "WAVE")

            writeBridgeAscii(out, "fmt ")
            writeBridgeLeInt(out, 16)
            writeBridgeLeShort(out, 1)
            writeBridgeLeShort(out, channels)
            writeBridgeLeInt(out, sampleRate)
            writeBridgeLeInt(out, byteRate)
            writeBridgeLeShort(out, blockAlign)
            writeBridgeLeShort(out, bitsPerSample)

            writeBridgeAscii(out, "data")
            writeBridgeLeInt(out, dataSize)

            src.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }

    private fun bridgeExportCopyOrWrapAudio(src: File, dst: File, item: JSONObject) {
        if (bridgeExportIsRawPcm(src, item)) {
            writeBridgeRawPcmAsWav(src, dst, item)
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }


    fun exportReaderAudioCacheForBridge(
        context: Context,
        requestId: String,
        sessionId: String,
        contentHash: String,
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        providerAuthority: String,
    ): JSONObject {
        val root = File(context.getExternalFilesDir(null), "reader_audio_cache")
        if (!root.exists()) root.mkdirs()

        val exportDir = File(root, "exports/${bridgeSafeFileName(requestId)}")
        if (!exportDir.exists()) exportDir.mkdirs()

        val manifestFile = bridgeFindBestAudioManifest(
            context = context,
            bookName = bookName,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
        ) ?: throw IllegalStateException("未找到 AudioCacheFactory 已缓存音频 manifest。请先让 J.TTS 朗读/生成一些片段后再导出。")

        val sourceManifest = runCatching {
            JSONObject(manifestFile.readText())
        }.getOrElse {
            throw IllegalStateException("读取 AudioCacheFactory manifest 失败: ${manifestFile.absolutePath} ${it.message}")
        }

        val sourceItems = sourceManifest.optJSONArray("items") ?: JSONArray()
        val segments = JSONArray()

        for (i in 0 until sourceItems.length()) {
            val item = sourceItems.optJSONObject(i) ?: continue
            val path = item.optString("path", "")
            if (path.isBlank()) continue

            val src = File(path)
            if (!src.exists() || !src.isFile || src.length() <= 0L) continue
            if (!isAudioCacheFile(src)) continue

            val index = item.optInt("index", i)
            val ext = bridgeExportTargetExtension(src, item)

            val outName = "%06d_%s.%s".format(
                index.coerceAtLeast(i),
                bridgeSafeFileName(item.optString("textHash", "").ifBlank { src.nameWithoutExtension }),
                ext
            )
            val dst = File(exportDir, outName)

            val expectedSize = if (bridgeExportIsRawPcm(src, item)) {
                src.length() + 44L
            } else {
                src.length()
            }

            if (!dst.exists() || dst.length() != expectedSize) {
                bridgeExportCopyOrWrapAudio(src, dst, item)
            }

            val audioUri = bridgeUriForFile(root, dst, providerAuthority)

            val seg = JSONObject()
            seg.put("index", index)
            seg.put("order", i)
            seg.put("text", item.optString("text", ""))
            seg.put("textHash", item.optString("textHash", ""))
            seg.put("lookupTextHash", item.optString("lookupTextHash", ""))
            seg.put("tag", item.optString("tag", ""))
            seg.put("voice", item.optString("voice", ""))
            seg.put("actualVoice", item.optString("actualVoice", ""))
            seg.put("emotion", item.optString("emotion", ""))
            seg.put("startOffset", item.optInt("startOffset", -1))
            seg.put("endOffset", item.optInt("endOffset", -1))
            seg.put("durationMs", item.optLong("durationMs", -1L))
            seg.put("audioUri", audioUri.toString())
            seg.put("audioMimeType", bridgeExportedAudioMimeType(src, item))
            seg.put("sourceAudioMimeType", bridgeExportAudioMimeType(src, item))
            seg.put("wrappedFromPcm", bridgeExportIsRawPcm(src, item))
            seg.put("sampleRate", bridgeExportSampleRate(item))
            seg.put("channels", bridgeExportChannelCount(item))
            seg.put("channelCount", bridgeExportChannelCount(item))
            seg.put("bitsPerSample", bridgeExportBitsPerSample(item))
            seg.put("sizeBytes", dst.length())
            seg.put("fileName", dst.name)
            segments.put(seg)
        }

        if (segments.length() == 0) {
            throw IllegalStateException("AudioCacheFactory manifest 存在，但没有可导出的音频片段: ${manifestFile.absolutePath}")
        }

        val out = JSONObject()
        out.put("method", "exportReaderAudioCache")
        out.put("status", "done")
        out.put("requestId", requestId)
        out.put("sessionId", sessionId)
        out.put("contentHash", contentHash)
        out.put("bookName", bookName.ifBlank { sourceManifest.optString("bookName", "") })
        out.put("chapterTitle", chapterTitle.ifBlank { sourceManifest.optString("chapterTitle", "") })
        out.put("chapterIndex", if (chapterIndex >= 0) chapterIndex else sourceManifest.optInt("chapterIndex", -1))
        out.put("sourceManifestPath", manifestFile.absolutePath)
        out.put("segmentCount", segments.length())
        out.put("segments", segments)
        out.put("createdAt", System.currentTimeMillis())

        val exportManifest = File(exportDir, "manifest.json")
        out.put("manifestUri", bridgeUriForFile(root, exportManifest, providerAuthority).toString())
        exportManifest.writeText(out.toString(2))

        appendPreviewLog(
            context = context,
            source = "缓存片段导出",
            message = "已导出 AudioCacheFactory 缓存片段｜数量=${segments.length()}｜manifest=${exportManifest.absolutePath}"
        )

        return out
    }

    private fun bridgeFindBestAudioManifest(
        context: Context,
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        contentHash: String,
    ): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val manifests = root.walkTopDown()
            .filter { it.isFile && it.name == "manifest.json" }
            .filter { file ->
                runCatching {
                    val text = file.readText()
                    text.contains("\"items\"") && text.contains("\"path\"")
                }.getOrDefault(false)
            }
            .toList()

        if (manifests.isEmpty()) return null

        fun score(file: File): Int {
            val text = runCatching { file.readText() }.getOrDefault("")
            var s = 0
            if (contentHash.isNotBlank() && text.contains(contentHash)) s += 1000
            if (bookName.isNotBlank() && text.contains(bookName)) s += 300
            if (chapterTitle.isNotBlank() && text.contains(chapterTitle)) s += 300
            if (chapterIndex >= 0 && text.contains("\"chapterIndex\":$chapterIndex")) s += 200
            if (chapterIndex >= 0 && text.contains("\"chapterIndex\": $chapterIndex")) s += 200
            s += ((file.lastModified() / 1000L) % 100000L).toInt()
            return s
        }

        return manifests.maxWithOrNull(compareBy<File> { score(it) }.thenBy { it.lastModified() })
    }

    private fun bridgeUriForFile(root: File, file: File, authority: String): Uri {
        val rootFile = root.canonicalFile
        val target = file.canonicalFile

        val rootPath = rootFile.absolutePath
        val filePath = target.absolutePath

        require(filePath == rootPath || filePath.startsWith(rootPath + File.separator)) {
            "file outside export root: $filePath"
        }

        val rel = if (filePath == rootPath) "" else filePath.substring(rootPath.length + 1)
        val builder = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath("reader_audio_cache")

        if (rel.isNotBlank()) {
            rel.split(File.separatorChar).forEach { part ->
                if (part.isNotBlank()) builder.appendPath(part)
            }
        }

        return builder.build()
    }

    private fun bridgeGuessMime(file: File): String {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "pcm" -> "audio/pcm"
            else -> "application/octet-stream"
        }
    }

    private fun bridgeSafeFileName(value: String): String {
        return value.ifBlank { "item" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }


}
