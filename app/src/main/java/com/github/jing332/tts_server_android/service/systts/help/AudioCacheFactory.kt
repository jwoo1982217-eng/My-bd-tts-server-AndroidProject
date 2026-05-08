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
        val configFingerprint = currentConfigFingerprint()

        for (i in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(i) ?: continue
            val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
            val manifest = readManifest(chapterDir(context, bookKey, chapterKey)) ?: continue
            val items = manifest.optJSONArray("items") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (item.optString("textHash") != textHash) continue
                if (item.optString("configFingerprint") != configFingerprint) continue

                val file = File(item.optString("path", ""))
                if (!file.exists() || file.length() <= 0) continue

                return CachedAudio(
                    sampleRate = item.optInt("sampleRate", 16000).coerceAtLeast(8000),
                    bytes = file.readBytes(),
                    chapterKey = chapterKey,
                    index = item.optInt("index", -1),
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
            if (hasItem(manifest, item.index, item.text, configFingerprint)) continue

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
                            "bookUrl" to chapter.optString("bookUrl", ""),
                            "chapterIndex" to chapter.optInt("chapterIndex", -1),
                            "chapterTitle" to chapter.optString("chapterTitle", ""),
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
            val text = raw["text"]?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@mapIndexedNotNull null

            val index = raw["index"]?.toString()?.toIntOrNull() ?: fallbackIndex
            QueueItem(
                index = index,
                text = text,
                tag = raw["tag"]?.toString().orEmpty(),
                voice = raw["voice"]?.toString().orEmpty(),
                emotion = raw["emotion"]?.toString().orEmpty(),
                speed = raw["speed"]?.toString()?.toFloatOrNull() ?: 1f,
                pitch = raw["pitch"]?.toString()?.toFloatOrNull() ?: 1f,
                volume = raw["volume"]?.toString()?.toFloatOrNull() ?: 1f,
                raw = JSONObject(raw)
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

        val exact = dbm.speechRuleDao.getByRuleId(ruleId) ?: return null
        if (!exact.isModule) return exact

        return dbm.speechRuleDao.all.firstOrNull {
            it.projectId == exact.projectId && !it.isModule &&
                (it.moduleId == "main" || it.moduleType == "main" || it.moduleType == "pipeline_entry")
        } ?: dbm.speechRuleDao.all.firstOrNull {
            it.projectId == exact.projectId && !it.isModule
        }
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
        index: Int,
        text: String,
        configFingerprint: String,
    ): Boolean {
        val items = manifest.optJSONArray("items") ?: return false
        val textHash = text.md5()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("index", -1) != index && item.optString("textHash") != textHash) continue
            if (item.optString("configFingerprint") != configFingerprint) continue
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
        return File(context.getExternalFilesDir("reader_audio_cache"), "$bookKey/$chapterKey")
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
}
