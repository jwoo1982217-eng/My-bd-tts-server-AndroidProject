package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import com.drake.net.Net
import com.github.jing332.common.utils.limitLength
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.engine.RhinoScriptEngine
import com.github.jing332.script.ensureArgumentsLength
import com.github.jing332.script.runtime.NativeResponse
import com.github.jing332.script.runtime.console.Console
import com.github.jing332.script.simple.CompatScriptRuntime
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.tts.speech.EmptyInputStream
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import okhttp3.Response
import okhttp3.ResponseBody
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class TtsPluginEngineV2(val context: Context, var plugin: Plugin) {
    companion object {
        const val OBJ_PLUGIN_JS = "PluginJS"

        const val FUNC_GET_AUDIO = "getAudio"
        const val FUNC_GET_AUDIO_V2 = "getAudioV2"
        const val FUNC_ON_LOAD = "onLoad"
        const val FUNC_ON_STOP = "onStop"
    }

    var console: Console
        get() = engine.runtime.console
        set(value) {
            engine.runtime.console = value
            bindPluginConsoleLogger()
        }

    protected val ttsrv = TtsEngineContext(
        tts = PluginTtsSource(),
        userVars = plugin.userVars,
        context = context,
        engineId = plugin.pluginId
    )

    val runtime = CompatScriptRuntime(ttsrv)

    var source: PluginTtsSource
        get() = ttsrv.tts
        set(value) {
            ttsrv.tts = value
        }

    protected val pluginJsObj: ScriptableObject
        get() = (engine.get(OBJ_PLUGIN_JS) as? ScriptableObject)
            ?: throw IllegalStateException("Object `$OBJ_PLUGIN_JS` not found")

    protected var engine: RhinoScriptEngine = RhinoScriptEngine(runtime)

    private fun appendPluginLog(context: Context, message: String) {
        runCatching {
            val dir = context.getExternalFilesDir("log") ?: return
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "system_tts.log")
            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(Date())

            file.appendText(
                "$time I\n[Plugin] $message\n",
                Charsets.UTF_8
            )
        }
    }

    private fun bindPluginConsoleLogger() {
        engine.runtime.console.addLogListener { entry ->
            appendPluginLog(
                context = context.applicationContext,
                message = entry.message.toString()
            )
        }
    }

    init {
        bindPluginConsoleLogger()
    }

    open protected fun execute(script: String): Any? =
        engine.execute(script.toScriptSource(sourceName = plugin.pluginId))

    @Suppress("UNCHECKED_CAST")
    fun eval() {
        execute(plugin.code)

        pluginJsObj.apply {
            plugin.name = get("name").toString()
            plugin.pluginId = get("id").toString()
            plugin.author = get("author").toString()
            plugin.iconUrl = get("iconUrl")?.toString() ?: ""

            try {
                plugin.defVars = get("vars") as Map<String, Map<String, String>>
            } catch (_: NullPointerException) {
                plugin.defVars = emptyMap()
            } catch (t: Throwable) {
                plugin.defVars = emptyMap()

                throw ClassCastException("\"vars\" bad format").initCause(t)
            }

            plugin.version = try {
                org.mozilla.javascript.Context.toNumber(get("version")).toInt()
            } catch (e: Exception) {
                -1
            }
        }
    }

    fun onLoad(): Any? {
        return try {
            engine.invokeMethod(pluginJsObj, FUNC_ON_LOAD)
        } catch (_: NoSuchMethodException) {
        }
    }

    fun onStop(): Any? {
        return try {
            engine.invokeMethod(pluginJsObj, FUNC_ON_STOP)
        } catch (_: NoSuchMethodException) {
        }
    }

    private fun ResponseBody.check(): ResponseBody {
        val type = contentType()?.toString() ?: return this
        if (type.startsWith("text") || type.startsWith("application/json")) {
            throw IllegalStateException("Unexpected Response: ${this.string().limitLength(500)}")
        }

        return this
    }

    private fun hasSpeakableChar(text: String): Boolean {
        return text.any { it.isLetterOrDigit() }
    }

    private fun shouldReturnSilentAudio(text: String): Boolean {
        return text.trim().isBlank() || !hasSpeakableChar(text)
    }

    private fun createSilentWavInputStream(
        durationMs: Int = 20,
        sampleRate: Int = 44100,
    ): InputStream {
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val sampleCount = sampleRate * durationMs / 1000
        val dataSize = sampleCount * channels * bytesPerSample
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample

        fun ByteArrayOutputStream.writeAscii(value: String) {
            write(value.toByteArray(Charsets.US_ASCII))
        }

        fun ByteArrayOutputStream.writeIntLE(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
            write((value shr 16) and 0xFF)
            write((value shr 24) and 0xFF)
        }

        fun ByteArrayOutputStream.writeShortLE(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
        }

        val out = ByteArrayOutputStream(44 + dataSize)

        out.writeAscii("RIFF")
        out.writeIntLE(36 + dataSize)
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(channels)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(bitsPerSample)

        out.writeAscii("data")
        out.writeIntLE(dataSize)
        out.write(ByteArray(dataSize))

        return ByteArrayInputStream(out.toByteArray())
    }

    private fun handleAudioResult(result: Any?): InputStream? {
        if (result == null) return null

        return when (result) {
            is NativeArrayBuffer -> ByteArrayInputStream(result.buffer)
            is NativeTypedArrayView<*> -> ByteArrayInputStream(result.buffer.buffer)

            is InputStream -> result
            is ByteArray -> result.inputStream()

            is PipedOutputStream -> {
                val pis = PipedInputStream(result)
                return pis
            }

            is NativeResponse -> result.rawResponse?.body?.check()?.byteStream()

            is CharSequence -> {
                val str = result.toString()
                if (str.startsWith("http://") || str.startsWith("https://")) {
                    val resp: Response = Net.get(str).execute()
                    return resp.body?.check()?.byteStream()
                } else {
                    throw IllegalStateException(str)
                }
            }

            is Undefined -> null

            else -> throw IllegalArgumentException(
                "getAudio() return type not support: ${result.javaClass.name}"
            )
        }
    }

    private val mMutex by lazy { Mutex() }

    private suspend fun newCallback(ins: JsBridgeInputStream): Scriptable {
        val callback = ins.getCallback(mMutex)

        return org.mozilla.javascript.Context.enter().use { cx ->
            cx.newObject(engine.scope ?: engine.globalScope)
                .apply {
                    put("write", this, object : Callable {
                        override fun call(
                            cx: org.mozilla.javascript.Context,
                            scope: Scriptable,
                            thisObj: Scriptable,
                            args: Array<out Any?>,
                        ): Any = ensureArgumentsLength(args, 1) {
                            callback.write(args[0])
                            Undefined.instance
                        }
                    })

                    put("close", this, object : Callable {
                        override fun call(
                            cx: org.mozilla.javascript.Context?,
                            scope: Scriptable?,
                            thisObj: Scriptable?,
                            args: Array<out Any?>?,
                        ): Any {
                            callback.close()
                            return Undefined.instance
                        }
                    })

                    put("error", this, object : Callable {
                        override fun call(
                            cx: org.mozilla.javascript.Context,
                            scope: Scriptable,
                            thisObj: Scriptable,
                            args: Array<out Any?>,
                        ): Any = ensureArgumentsLength(args, 1) {
                            callback.error(args[0])
                            Undefined.instance
                        }
                    })
                }
        }
    }

    private suspend fun getAudioV2(request: Map<String, Any>): InputStream {
        val ins = JsBridgeInputStream()
        val jsObj = newCallback(ins)

        val result = runInterruptible {
            engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO_V2, request, jsObj)
                ?: throw NoSuchMethodException("getAudioV2() not found")
        }

        return handleAudioResult(result) ?: ins
    }

    suspend fun getAudio(
        text: String,
        locale: String,
        voice: String,
        rate: Float = 1f,
        volume: Float = 1f,
        pitch: Float = 1f,
    ): InputStream {
        if (shouldReturnSilentAudio(text)) {
            return createSilentWavInputStream(durationMs = 10)
        }

        val r = (rate * 50f).toInt()
        val v = (volume * 50f).toInt()
        val p = (pitch * 50f).toInt()

        val result = try {
            runInterruptible {
                engine.invokeMethod(
                    pluginJsObj,
                    FUNC_GET_AUDIO,
                    text,
                    locale,
                    voice,
                    r,
                    v,
                    p
                )
            }
        } catch (_: NoSuchMethodException) {
            val request = mapOf(
                "text" to text,
                "locale" to locale,
                "voice" to voice,
                "rate" to r,
                "speed" to r,
                "volume" to v,
                "pitch" to p
            )

            getAudioV2(request)
        }

        return handleAudioResult(result) ?: createSilentWavInputStream(durationMs = 10)
    }
}