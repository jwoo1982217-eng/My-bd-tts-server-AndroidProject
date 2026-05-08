package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.tts.MixSynthesizer
import com.github.jing332.tts.SynthesizerContext
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import com.github.jing332.database.entities.systts.AudioParams
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

    fun getCachedAudio(context: Context, text: String): CachedAudio? {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return null

        val window = ReaderChapterBridgeClient.fetchChapterWindowJson()
        if (!window.optBoolean("ok", false)) return null

        val bookKey = window.optString("bookUrl", window.optString("bookName", "")).md5()
        val chapters = window.optJSONArray("chapters") ?: return null
        val textHash = cleanText.md5()

        for (i in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(i) ?: continue
            val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
            val manifest = readManifest(chapterDir(context, bookKey, chapterKey)) ?: continue
            val items = manifest.optJSONArray("items") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (item.optString("textHash") != textHash) continue

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
        val text = chapter.optString("text", "")
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return

        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        manifest.put("status", "caching_audio")
        writeManifest(dir, manifest)

        for ((index, sentence) in sentences.withIndex()) {
            if (hasItem(manifest, index, sentence)) continue

            val audio = synthesizeToPcm(manager, sentence) ?: continue
            saveItem(
                context = context,
                bookKey = bookKey,
                bookName = bookName,
                chapter = chapter,
                index = index,
                text = sentence,
                sampleRate = audio.first,
                bytes = audio.second,
                status = "caching_audio"
            )
        }

        val latest = readManifest(dir) ?: manifest
        latest.put("status", "ready")
        writeManifest(dir, latest)
    }

    private suspend fun synthesizeToPcm(
        manager: MixSynthesizer,
        text: String,
    ): Pair<Int, ByteArray>? {
        val out = ByteArrayOutputStream()
        var sampleRate = 16000

        manager.synthesize(
            params = SystemParams(text = text),
            forceConfigId = null,
            callback = object : com.github.jing332.tts.synthesizer.SynthesisCallback {
                override fun onSynthesizeStart(sampleRateInHz: Int) {
                    if (sampleRateInHz > 0) sampleRate = sampleRateInHz
                }

                override fun onSynthesizeAvailable(audio: ByteArray) {
                    if (audio.isNotEmpty()) out.write(audio)
                }
            }
        )

        return out.toByteArray()
            .takeIf { it.isNotEmpty() }
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
        status: String,
    ) {
        val chapterKey = chapterKey(bookKey, chapter.optInt("chapterIndex", -1))
        val dir = chapterDir(context, bookKey, chapterKey)
        if (!dir.exists()) dir.mkdirs()

        val audioFile = File(dir, "${index}_${text.md5()}.pcm")
        audioFile.writeBytes(bytes)

        val manifest = readManifest(dir) ?: newManifest(bookName, chapter, chapterKey)
        val items = manifest.optJSONArray("items") ?: JSONArray().also { manifest.put("items", it) }
        upsertItem(
            items = items,
            item = JSONObject()
                .put("index", index)
                .put("text", text)
                .put("textHash", text.md5())
                .put("sampleRate", sampleRate.coerceAtLeast(8000))
                .put("path", audioFile.absolutePath)
                .put("status", "ready")
                .put("updatedAt", System.currentTimeMillis())
        )
        manifest.put("bookName", bookName)
        manifest.put("status", status)
        manifest.put("updatedAt", System.currentTimeMillis())
        writeManifest(dir, manifest)
    }

    private fun upsertItem(items: JSONArray, item: JSONObject) {
        val index = item.optInt("index", -1)
        val textHash = item.optString("textHash", "")
        for (i in 0 until items.length()) {
            val old = items.optJSONObject(i) ?: continue
            if (old.optInt("index", -2) == index || old.optString("textHash") == textHash) {
                items.put(i, item)
                return
            }
        }
        items.put(item)
    }

    private fun hasItem(manifest: JSONObject, index: Int, text: String): Boolean {
        val items = manifest.optJSONArray("items") ?: return false
        val textHash = text.md5()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("index", -1) != index && item.optString("textHash") != textHash) continue
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

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
