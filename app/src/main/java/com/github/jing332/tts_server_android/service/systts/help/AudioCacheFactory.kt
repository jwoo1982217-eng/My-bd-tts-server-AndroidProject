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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object AudioCacheFactory {
    private val logger = KotlinLogging.logger("AudioCacheFactory")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warming = AtomicBoolean(false)

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
                val manager = getBackgroundManager(context, liveManager)
                val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
                if (!window.optBoolean("ok", false)) return@launch

                val bookKey = window.optString("bookUrl", window.optString("bookName", "")).md5()
                val bookName = window.optString("bookName", "")
                val chapters = window.optJSONArray("chapters") ?: return@launch

                for (i in 0 until chapters.length()) {
                    val chapter = chapters.optJSONObject(i) ?: continue
                    if (!chapter.optBoolean("ok", false)) continue

                    cacheChapter(
                        context = context,
                        manager = manager,
                        bookKey = bookKey,
                        bookName = bookName,
                        chapter = chapter
                    )
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
                val manager = getBackgroundManager(context, liveManager)
                retryFailedItemsInternal(context, manager, bookKey, chapterKey)
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
                val manager = getBackgroundManager(context, liveManager)
                retryItemsInternal(context, manager, bookKey, chapterKey, setOf(itemIndex))
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
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        val configFingerprint = currentConfigFingerprint()
        val queue = prepareQueue(context, bookName, chapter)

        if (queue.isEmpty()) return

        writeQueue(dir, queue)
        manifest.put("status", "caching_audio")
        manifest.put("configFingerprint", configFingerprint)
        writeManifest(dir, manifest)

        for (item in queue) {
            if (hasItem(manifest, item, configFingerprint)) continue

            updateQueueItem(dir, item.index, "caching_audio")
            val config = resolveTtsConfig(manager, item)
            if (config == null) {
                updateQueueItem(dir, item.index, "failed", "No matching TTS config")
                continue
            }

            val audio = synthesizeQueueItemToPcm(manager, item, config)
            if (audio == null) {
                updateQueueItem(dir, item.index, "failed", "TTS request failed")
                continue
            }
            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = bookName,
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
        latest.put("status", "ready")
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

        if (queue.isNotEmpty()) return queue

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

    private fun resolveTtsConfig(manager: MixSynthesizer, item: QueueItem): TtsConfiguration? {
        val configs = runCatching { manager.repo.getAllTts().values.toList() }.getOrDefault(emptyList())
        if (configs.isEmpty()) return null

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

        val base = byVoice ?: byTag ?: configs.firstOrNull()
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

    private fun cacheRoot(context: Context): File {
        return context.getExternalFilesDir("reader_audio_cache")
            ?: File(context.filesDir, "reader_audio_cache")
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
