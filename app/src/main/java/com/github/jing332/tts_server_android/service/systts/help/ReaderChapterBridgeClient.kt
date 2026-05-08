
package com.github.jing332.tts_server_android.service.systts.help

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * 阅读 App 章节直通桥。
 *
 * 默认读取：
 * http://127.0.0.1:18326/tts/current-chapter
 *
 * 返回内容会写入朗读规则 ctx.readerChapter / ctx.readerChapterWindow。
 */
object ReaderChapterBridgeClient {
    private const val DEFAULT_URL = "http://127.0.0.1:18326/tts/current-chapter"
    private const val CHAPTER_CONTENT_URL = "http://127.0.0.1:18326/getBookContent"
    private const val CACHE_TTL_MS = 5000L
    private const val DEFAULT_PRELOAD_COUNT = 4
    private const val MAX_PRELOAD_COUNT = 50

    @Volatile
    private var lastFetchAt: Long = 0L

    @Volatile
    private var lastJsonText: String? = null

    @Volatile
    private var lastWindowFetchAt: Long = 0L

    @Volatile
    private var lastWindowJsonText: String? = null

    @Synchronized
    fun fetchCurrentChapterJson(): JSONObject {
        val now = System.currentTimeMillis()

        val cached = lastJsonText
        if (cached != null && now - lastFetchAt < CACHE_TTL_MS) {
            return try {
                JSONObject(cached)
            } catch (_: Exception) {
                buildError("CacheParseError", "cached readerChapter json parse failed")
            }
        }

        val result = fetchFromReaderApp()
        lastFetchAt = now
        lastJsonText = result.toString()
        return result
    }

    private fun fetchFromReaderApp(): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(DEFAULT_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 700
            conn.readTimeout = 1200
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }

            if (code !in 200..299) {
                return buildError("Http$code", body.take(300))
            }

            val root = JSONObject(body)
            val data = root.optJSONObject("data")
                ?: return buildError("NoData", "reader app response has no data object")

            val chapterText = data.optString("text", "")
            val hash = data.optString("textHash", "")

            data.put("ok", chapterText.isNotBlank())
            data.put("bridgeUrl", DEFAULT_URL)
            data.put("fetchedAt", System.currentTimeMillis())

            if (chapterText.isBlank()) {
                data.put("error", "chapter text is blank")
            }
            if (hash.isBlank()) {
                data.put("warning", "textHash is blank")
            }

            data
        } catch (e: Exception) {
            buildError(e.javaClass.simpleName, e.message ?: "")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildError(type: String, message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("errorType", type)
            .put("error", message)
            .put("bridgeUrl", DEFAULT_URL)
            .put("fetchedAt", System.currentTimeMillis())
    }

    @Synchronized
    fun fetchChapterWindowJson(): JSONObject {
        val now = System.currentTimeMillis()

        val cached = lastWindowJsonText
        if (cached != null && now - lastWindowFetchAt < CACHE_TTL_MS) {
            return try {
                JSONObject(cached)
            } catch (_: Exception) {
                buildWindowError("CacheParseError", "cached readerChapterWindow json parse failed")
            }
        }

        val current = fetchCurrentChapterJson()
        val result = buildChapterWindow(current)
        lastWindowFetchAt = now
        lastWindowJsonText = result.toString()
        return result
    }

    private fun buildChapterWindow(current: JSONObject): JSONObject {
        if (!current.optBoolean("ok", false)) {
            return buildWindowError(
                type = current.optString("errorType", "CurrentChapterUnavailable"),
                message = current.optString("error", "current chapter unavailable")
            ).put("current", current)
        }

        val bookUrl = current.optString("bookUrl", "")
        val currentIndex = current.optInt("chapterIndex", -1)
        if (bookUrl.isBlank() || currentIndex < 0) {
            return buildWindowError("BadCurrentChapter", "bookUrl or chapterIndex is invalid")
                .put("current", current)
        }

        val preloadCount = resolvePreloadCount(current)
        val preloadCountSource = resolvePreloadCountSource(current)
        val chapters = JSONArray()
        chapters.put(
            JSONObject()
                .put("bookUrl", bookUrl)
                .put("bookName", current.optString("bookName", ""))
                .put("author", current.optString("author", ""))
                .put("origin", current.optString("origin", ""))
                .put("chapterUrl", current.optString("chapterUrl", ""))
                .put("chapterIndex", currentIndex)
                .put("chapterTitle", current.optString("chapterTitle", ""))
                .put("text", current.optString("text", ""))
                .put("textLen", current.optInt("textLen", current.optString("text", "").length))
                .put("textHash", current.optString("textHash", ""))
                .put("isCurrent", true)
        )

        for (offset in 1..preloadCount) {
            val targetIndex = currentIndex + offset
            val chapter = fetchChapterByIndex(bookUrl, targetIndex)
            if (!chapter.optBoolean("ok", false)) {
                chapters.put(chapter.put("chapterIndex", targetIndex))
                continue
            }
            chapters.put(chapter)
        }

        return JSONObject()
            .put("ok", true)
            .put("bookUrl", bookUrl)
            .put("bookName", current.optString("bookName", ""))
            .put("currentChapterIndex", currentIndex)
            .put("preloadCount", preloadCount)
            .put("preloadCountSource", preloadCountSource)
            .put("chapterCount", chapters.length())
            .put("chapters", chapters)
            .put("current", current)
            .put("bridgeUrl", DEFAULT_URL)
            .put("contentUrl", CHAPTER_CONTENT_URL)
            .put("fetchedAt", System.currentTimeMillis())
    }

    private fun fetchChapterByIndex(bookUrl: String, chapterIndex: Int): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            val url = "$CHAPTER_CONTENT_URL?url=${bookUrl.urlEncode()}&index=$chapterIndex"
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 1200
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }

            if (code !in 200..299) {
                return buildChapterError(chapterIndex, "Http$code", body.take(300))
            }

            val root = JSONObject(body)
            if (root.optString("errorMsg", "").isNotBlank()) {
                return buildChapterError(chapterIndex, "ReaderError", root.optString("errorMsg"))
            }

            val text = root.optString("data", "")
            if (text.isBlank()) {
                return buildChapterError(chapterIndex, "BlankContent", "chapter content is blank")
            }

            JSONObject()
                .put("ok", true)
                .put("bookUrl", bookUrl)
                .put("chapterIndex", chapterIndex)
                .put("chapterTitle", "")
                .put("text", text)
                .put("textLen", text.length)
                .put("textHash", text.md5())
                .put("isCurrent", false)
        } catch (e: Exception) {
            buildChapterError(chapterIndex, e.javaClass.simpleName, e.message ?: "")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolvePreloadCount(current: JSONObject): Int {
        if (current.has("audioPreloadEnabled") && !current.optBoolean("audioPreloadEnabled", true)) {
            return 0
        }

        val fromReader = listOf(
            "audioPreDownloadNum",
            "readAloudPreloadCount",
            "preloadCount",
            "chapterPreloadCount"
        ).firstNotNullOfOrNull { key ->
            current.optInt(key, -1).takeIf { it >= 0 }
        }

        return (fromReader ?: DEFAULT_PRELOAD_COUNT).coerceIn(0, MAX_PRELOAD_COUNT)
    }

    private fun resolvePreloadCountSource(current: JSONObject): String {
        if (current.has("audioPreloadEnabled") && !current.optBoolean("audioPreloadEnabled", true)) {
            return "reader_disabled"
        }

        return listOf(
            "audioPreDownloadNum",
            "readAloudPreloadCount",
            "preloadCount",
            "chapterPreloadCount"
        ).firstOrNull { key ->
            current.optInt(key, -1) >= 0
        } ?: "default"
    }

    private fun buildWindowError(type: String, message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("errorType", type)
            .put("error", message)
            .put("bridgeUrl", DEFAULT_URL)
            .put("contentUrl", CHAPTER_CONTENT_URL)
            .put("fetchedAt", System.currentTimeMillis())
    }

    private fun buildChapterError(chapterIndex: Int, type: String, message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("chapterIndex", chapterIndex)
            .put("errorType", type)
            .put("error", message)
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    private fun String.md5(): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
