package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.MixSynthesizer
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
            val realBookName = resolveRoleBookName(window.optString("bookName", ""), chapter)

            // 实时播放保存缓存时，也顺手保证角色管理当前书名不是“默认”
            ensureRoleManagerBookContext(context, realBookName)

            val sentences = splitSentences(chapter.optString("text", ""))
            val index = sentences.indexOfFirst { it.trim() == text.trim() }.takeIf { it >= 0 }
                ?: nextIndexForPlayback(context, bookKey, chapter)

            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = realBookName,
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
        return chapterDir(context, bookKey, chapterKey).deleteRecursively()
    }

    fun clearAll(context: Context): Boolean {
        return cacheRoot(context).deleteRecursively()
    }

    fun appendPreviewLog(context: Context, source: String, message: String) {
        runCatching {
            val file = previewLogFile(context)
            if (!file.parentFile.exists()) file.parentFile.mkdirs()

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
        if (hasItem(latestManifest, item, configFingerprint)) {
            updateQueueItem(dir, item.index, "ready")
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
            bookName = realBookName,
            chapter = chapter,
            index = item.index,
            text = item.text,
            sampleRate = audio.first,
            bytes = audio.second,
            queueItem = item,
            status = "caching_audio"
        )

        updateQueueItem(dir, item.index, "ready")
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
                sampleRate = audio.first,
                bytes = audio.second,
                queueItem = item,
                status = "caching_audio"
            )
            updateQueueItem(dir, item.index, "ready")
        }

        val latest = readManifest(dir) ?: manifest
        latest.put("status", if (readQueue(dir).any { it.raw.optString("status") == "failed" }) "failed" else "ready")
        writeManifest(dir, latest)
        return true
    }

    private suspend fun synthesizeQueueItemToPcm(
        manager: MixSynthesizer,
        item: QueueItem,
        config: TtsConfiguration,
    ): Pair<Int, ByteArray>? {
        val out = ByteArrayOutputStream()
        val sampleRate = config.audioFormat.sampleRate.coerceAtLeast(8000)
        val request = RequestPayload(SystemParams(text = item.text), config)

        var ok = false
        manager.ttsRequester.request(request.params, request.config)
            .onSuccess { resp ->
                val stream = resp.stream ?: return@onSuccess
                manager.streamProcessor.processStream(
                    ins = stream,
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
            ?.let { sampleRate to it }
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

    // 关键修复：
    // 不再优先调用 prepareChapterAudioQueueIfExists。
    // 回到魔改前的旧规则 handleText 路线，让朗读规则自己处理：
    // getBookshelf / cunfang.txt / liebiao.json / shuming.<书名>.json / characterRecords.json。
    if (rule != null) {
        val legacyQueue = prepareLegacyRuleQueue(
            context = context,
            manager = manager,
            rule = rule,
            chapterText = chapterText,
            chapter = chapter
        )

        appendPreviewLog(
            context = context,
            source = "朗读规则运行区",
            message = "${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜旧handleText路线｜生成 ${legacyQueue.size} 句"
        )

        if (legacyQueue.isNotEmpty()) return legacyQueue

        appendPreviewLog(
            context = context,
            source = "朗读规则运行区",
            message = "${chapter.optInt("chapterIndex", -1)} ${chapter.optString("chapterTitle", "")}｜旧handleText未生成有效队列，停止本章缓存。"
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
        queueItem: QueueItem? = null,
        status: String,
    ) {
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        if (!dir.exists()) dir.mkdirs()

        val configFingerprint = currentConfigFingerprint()
        val audioFile = File(dir, "${index}_${text.md5()}_${configFingerprint.take(12)}.pcm")
        audioFile.writeBytes(bytes)

        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        val items = manifest.optJSONArray("items") ?: JSONArray().also { manifest.put("items", it) }
        upsertItem(
            items = items,
            item = JSONObject()
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
        )
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
    return bookName
        .trim()
        .ifBlank { chapter.optString("bookName", "").trim() }
        .ifBlank { chapter.optString("bookTitle", "").trim() }
        .ifBlank { chapter.optString("name", "").trim() }
        .ifBlank { "默认" }
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
    // 关键修复：
    // 不在 APK 层写 cunfang.txt / liebiao.json / characterRecords.json。
    // 这些继续交给旧朗读规则 handleText + 角色管理插件处理，避免 APK 和 JS 两边抢写。
    appendPreviewLog(
        context = context,
        source = "角色管理",
        message = "跳过APK预切书｜交由朗读规则handleText维护书籍上下文｜book=$bookName"
    )
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
}
