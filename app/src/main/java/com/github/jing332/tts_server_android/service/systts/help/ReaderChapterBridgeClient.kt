
package com.github.jing332.tts_server_android.service.systts.help

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 阅读 App 当前章节直通桥。
 *
 * 默认读取：
 * http://127.0.0.1:1122/tts/current-chapter
 *
 * 返回内容会写入朗读规则 ctx.readerChapter。
 */
object ReaderChapterBridgeClient {
    private const val DEFAULT_URL = "http://127.0.0.1:1122/tts/current-chapter"
    private const val CACHE_TTL_MS = 5000L

    @Volatile
    private var lastFetchAt: Long = 0L

    @Volatile
    private var lastJsonText: String? = null

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
}
