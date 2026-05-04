package com.github.jing332.script.simple.ext

import com.drake.net.Net
import com.drake.net.exception.ConvertException
import com.github.jing332.script.annotation.ScriptInterface
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

open class JsNet(private val engineId: String) {
    private val groupId by lazy { engineId + hashCode() }

    /**
     * 这里专门记录由本类直接发出的 OkHttp 请求。
     * 因为 httpPost 已经改成直接 OkHttp，不再走 Drake Net，所以 cancelNetwork 需要额外 cancel 这些 call。
     */
    private val runningCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    fun cancelNetwork() {
        Net.cancelGroup(groupId)

        synchronized(runningCalls) {
            runningCalls.forEach {
                try {
                    it.cancel()
                } catch (_: Throwable) {
                }
            }
            runningCalls.clear()
        }
    }

    @JvmOverloads
    @ScriptInterface
    fun httpGet(url: CharSequence, headers: Map<CharSequence, CharSequence>? = null): Response {
        return Net.get(url.toString()) {
            setGroup(groupId)
            headers?.let {
                it.forEach {
                    setHeader(it.key.toString(), it.value.toString())
                }
            }
        }.execute()
    }

    /**
     * HTTP GET
     */
    @JvmOverloads
    @ScriptInterface
    fun httpGetString(
        url: CharSequence,
        headers: Map<CharSequence, CharSequence>? = null
    ): String? {
        return try {
            Net.get(url.toString()) {
                setGroup(groupId)
                headers?.let {
                    it.forEach {
                        setHeader(it.key.toString(), it.value.toString())
                    }
                }
            }.execute<String>()
        } catch (e: ConvertException) {
            throw Exception("Body is not a String, HTTP-${e.response.code}=${e.response.message}")
        }
    }

    @JvmOverloads
    @ScriptInterface
    fun httpGetBytes(
        url: CharSequence,
        headers: Map<CharSequence, CharSequence>? = null
    ): ByteArray? {
        return try {
            httpGet(url, headers).body?.bytes()
        } catch (e: ConvertException) {
            throw Exception("Body is not a Bytes, HTTP-${e.response.code}=${e.response.message}")
        }
    }

    /**
     * HTTP POST
     *
     * 重点修改：
     * 1. 不再使用 Drake Net.post，避免被默认 socket timeout 卡死。
     * 2. 改成 AI/大模型友好的 OkHttp 长超时。
     * 3. 保持原来的三参数调用兼容：ttsrv.httpPost(url, body, headers)
     * 4. 新增第四参数 timeoutMs：ttsrv.httpPost(url, body, headers, 120000)
     * 5. 如果 headers 里有 Timeout / X-Timeout，也会读取。
     */
    @JvmOverloads
    @ScriptInterface
    fun httpPost(
        url: CharSequence,
        body: CharSequence? = null,
        headers: Map<CharSequence, CharSequence>? = null,
        timeoutMs: Long = DEFAULT_HTTP_POST_TIMEOUT_MS
    ): Response {
        val effectiveTimeoutMs = resolveTimeoutMs(headers, timeoutMs)

        val requestBody = (body?.toString() ?: "").toRequestBody()

        val requestBuilder = Request.Builder()
            .url(url.toString())
            .post(requestBody)

        headers?.forEach { entry ->
            val key = entry.key.toString()
            val value = entry.value.toString()

            // Timeout / X-Timeout 是给 APK 网络层用的控制头，不再发给服务端。
            if (!isTimeoutControlHeader(key)) {
                requestBuilder.addHeader(key, value)
            }
        }

        val client = httpPostClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(effectiveTimeoutMs + CALL_TIMEOUT_EXTRA_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val call = client.newCall(requestBuilder.build())
        runningCalls.add(call)

        return try {
            call.execute()
        } finally {
            runningCalls.remove(call)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @ScriptInterface
    private fun postMultipart(type: String, form: Map<String, Any>): MultipartBody.Builder {
        val multipartBody = MultipartBody.Builder()
        multipartBody.setType(type.toMediaType())

        form.forEach { entry ->
            when (entry.value) {
                // 文件表单
                is Map<*, *> -> {
                    val filePartMap = entry.value as Map<String, Any>
                    val fileName = filePartMap["fileName"] as? String
                    val body = filePartMap["body"]
                    val contentType = filePartMap["contentType"] as? String

                    val mediaType = contentType?.toMediaType()
                    val requestBody = when (body) {
                        is File -> body.asRequestBody(mediaType)
                        is ByteArray -> body.toRequestBody(mediaType)
                        is String -> body.toRequestBody(mediaType)
                        else -> body.toString().toRequestBody()
                    }

                    multipartBody.addFormDataPart(entry.key, fileName, requestBody)
                }

                // 常规表单
                else -> multipartBody.addFormDataPart(entry.key, entry.value as String)
            }
        }

        return multipartBody
    }

    @JvmOverloads
    @ScriptInterface
    fun httpPostMultipart(
        url: CharSequence,
        form: Map<String, Any>,
        type: String = "multipart/form-data",
        headers: Map<CharSequence, CharSequence>? = null
    ): Response {
        return Net.post(url.toString()) {
            setGroup(groupId)
            headers?.let {
                it.forEach {
                    setHeader(it.key.toString(), it.value.toString())
                }
            }
            body = postMultipart(type, form).build()
        }.execute()
    }

    @JvmOverloads
    @ScriptInterface
    fun httpPostMultipartFile(
        url: CharSequence,
        form: Map<String, Any>,
        filePath: CharSequence,
        fileFieldName: CharSequence = "file",
        type: String = "multipart/form-data",
        headers: Map<CharSequence, CharSequence>? = null
    ): Response {
        val file = File(filePath.toString())

        require(file.exists()) {
            "文件不存在: ${file.absolutePath}"
        }
        require(file.isFile) {
            "不是文件: ${file.absolutePath}"
        }
        require(file.length() > 0L) {
            "文件为空: ${file.absolutePath}"
        }

        var result: Response? = null
        var error: Throwable? = null

        val thread = Thread(Runnable {
            try {
                val mimeType = when (file.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "flac" -> "audio/flac"
                    "opus" -> "audio/opus"
                    else -> "application/octet-stream"
                }

                val response: Response = Net.post(url.toString()) {
                    setGroup(groupId)

                    if (headers != null) {
                        for (entry in headers.entries) {
                            setHeader(entry.key.toString(), entry.value.toString())
                        }
                    }

                    val builder = postMultipart(type, form)

                    builder.addFormDataPart(
                        fileFieldName.toString(),
                        file.name,
                        file.asRequestBody(mimeType.toMediaType())
                    )

                    body = builder.build()
                }.execute()

                result = response
            } catch (t: Throwable) {
                error = t
            }
        })

        thread.start()
        thread.join()

        val err = error
        if (err != null) {
            throw RuntimeException(err.message ?: err.toString(), err)
        }

        return result ?: throw RuntimeException("上传失败：没有返回 Response")
    }

    @JvmOverloads
    @ScriptInterface
    fun httpPostMultipartFileRaw(
        url: CharSequence,
        form: Map<String, Any>,
        filePath: CharSequence,
        fileFieldName: CharSequence = "file",
        headers: Map<CharSequence, CharSequence>? = null
    ): String {
        val file = File(filePath.toString())

        require(file.exists()) {
            "文件不存在: ${file.absolutePath}"
        }
        require(file.isFile) {
            "不是文件: ${file.absolutePath}"
        }
        require(file.length() > 0L) {
            "文件为空: ${file.absolutePath}"
        }

        var result: String? = null
        var error: Throwable? = null

        val thread = Thread(Runnable {
            try {
                val mimeType = when (file.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "flac" -> "audio/flac"
                    "opus" -> "audio/opus"
                    else -> "application/octet-stream"
                }

                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)

                for (entry in form.entries) {
                    builder.addFormDataPart(entry.key, entry.value.toString())
                }

                builder.addFormDataPart(
                    fileFieldName.toString(),
                    file.name,
                    file.asRequestBody(mimeType.toMediaType())
                )

                val requestBuilder = Request.Builder()
                    .url(url.toString())
                    .post(builder.build())

                if (headers != null) {
                    for (entry in headers.entries) {
                        requestBuilder.addHeader(entry.key.toString(), entry.value.toString())
                    }
                }

                val client = httpPostClient.newBuilder()
                    .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(DEFAULT_UPLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(DEFAULT_UPLOAD_READ_TIMEOUT_MS + CALL_TIMEOUT_EXTRA_MS, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                val call = client.newCall(requestBuilder.build())
                runningCalls.add(call)

                val response = try {
                    call.execute()
                } finally {
                    runningCalls.remove(call)
                }

                val text = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    throw RuntimeException("上传失败 HTTP-${response.code}: $text")
                }

                result = text
            } catch (t: Throwable) {
                error = t
            }
        })

        thread.start()
        thread.join()

        val err = error
        if (err != null) {
            throw RuntimeException(err.message ?: err.toString(), err)
        }

        return result ?: ""
    }

    private fun resolveTimeoutMs(
        headers: Map<CharSequence, CharSequence>?,
        fallbackTimeoutMs: Long
    ): Long {
        val headerTimeoutMs = headers
            ?.entries
            ?.firstOrNull { isTimeoutControlHeader(it.key.toString()) }
            ?.value
            ?.toString()
            ?.trim()
            ?.toLongOrNull()

        val rawTimeoutMs = headerTimeoutMs ?: fallbackTimeoutMs

        // 为了重模型，最低不低于 60 秒；最高限制到 3 分钟，防止永久卡死。
        return rawTimeoutMs.coerceIn(
            MIN_HTTP_POST_TIMEOUT_MS,
            MAX_HTTP_POST_TIMEOUT_MS
        )
    }

    private fun isTimeoutControlHeader(key: String): Boolean {
        return key.equals("Timeout", ignoreCase = true) ||
                key.equals("X-Timeout", ignoreCase = true) ||
                key.equals("Read-Timeout", ignoreCase = true) ||
                key.equals("X-Read-Timeout", ignoreCase = true)
    }

    private companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_WRITE_TIMEOUT_MS = 60_000L

        // 普通 httpPost 默认给 120 秒，专门适配大模型首包慢的问题。
        private const val DEFAULT_HTTP_POST_TIMEOUT_MS = 120_000L

        // 即使规则里传 Timeout: 12000，也强制提升到 60 秒，避免重模型刚好被规则头部短超时掐掉。
        private const val MIN_HTTP_POST_TIMEOUT_MS = 60_000L

        // 最高 180 秒，避免网络异常时无限等。
        private const val MAX_HTTP_POST_TIMEOUT_MS = 180_000L

        // callTimeout 比 readTimeout 多给 30 秒，覆盖连接、写入、读取总耗时。
        private const val CALL_TIMEOUT_EXTRA_MS = 30_000L

        // 文件上传 raw 接口也顺手加长，避免默认 OkHttpClient 10 秒超时。
        private const val DEFAULT_UPLOAD_READ_TIMEOUT_MS = 120_000L

        private val httpPostClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_HTTP_POST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(DEFAULT_HTTP_POST_TIMEOUT_MS + CALL_TIMEOUT_EXTRA_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}