package com.github.jing332.server.forwarder

import android.util.Log
import com.github.jing332.server.BaseCallback
import com.github.jing332.server.CustomNetty
import com.github.jing332.server.Server
import com.github.jing332.server.installPlugins
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import java.io.File

class SystemTtsForwardServer(val port: Int, val callback: Callback) : Server {
    private fun safeText(value: Any?): String {
        return value?.toString().orEmpty()
    }

    private fun jsonEscape(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun jsonString(value: Any?): String {
        return "\"${jsonEscape(safeText(value))}\""
    }

    private fun jsonStringArray(values: Collection<String>?): String {
        if (values.isNullOrEmpty()) return "[]"

        return values.joinToString(
            prefix = "[",
            postfix = "]"
        ) {
            jsonString(it)
        }
    }

    private fun enginesToJson(engines: List<Engine>): String {
        return engines.joinToString(
            prefix = "[",
            postfix = "]"
        ) { engine ->
            buildString {
                append("{")
                append("\"name\":")
                append(jsonString(engine.name))
                append(",")
                append("\"label\":")
                append(jsonString(engine.label))
                append("}")
            }
        }
    }

    private fun voicesToJson(voices: List<Voice>): String {
        return voices.joinToString(
            prefix = "[",
            postfix = "]"
        ) { voice ->
            buildString {
                append("{")
                append("\"name\":")
                append(jsonString(voice.name))
                append(",")
                append("\"locale\":")
                append(jsonString(voice.locale))
                append(",")
                append("\"localeName\":")
                append(jsonString(voice.localeName))
                append(",")
                append("\"features\":")
                append(jsonStringArray(voice.features))
                append("}")
            }
        }
    }

    private val ktor by lazy {
        embeddedServer(CustomNetty, host = "0.0.0.0", port = port) {
            installPlugins()

            intercept(ApplicationCallPipeline.Call) {
                val method = call.request.httpMethod.value
                val uri = call.request.uri
                val remoteAddress = call.request.origin.remoteAddress

                callback.log(
                    level = Log.INFO,
                    "$method: ${uri.decodeURLQueryComponent()} \n remote: $remoteAddress \n"
                )
            }

            routing {
                staticResources("/", "forwarder")

                suspend fun RoutingContext.handleTts(
                    params: TtsParams,
                ) {
                    val file = callback.tts(params)
                    if (file == null) {
                        call.application.log.error("[InternalServerError] Android TTS Engine Error")
                        call.respond(HttpStatusCode.InternalServerError, "Android TTS Engine Error")
                    } else {
                        call.application.log.info("[OK] Android TTS Engine OK")

                        call.respondOutputStream(
                            ContentType.parse("audio/x-wav"),
                            HttpStatusCode.OK,
                            contentLength = file.length()
                        ) {
                            file.inputStream().use {
                                it.copyTo(this)
                            }
                            file.delete()
                        }
                    }
                }

                get("api/tts") {
                    val text = call.parameters.getOrFail("text")
                    val engine = call.parameters.getOrFail("engine")
                    val locale = call.parameters["locale"] ?: ""
                    val voice = call.parameters["voice"] ?: ""
                    val speed = (call.parameters["rate"] ?: call.parameters["speed"])
                        ?.toIntOrNull() ?: 50
                    val pitch = call.parameters["pitch"]?.toIntOrNull() ?: 100

                    handleTts(
                        TtsParams(
                            text = text,
                            engine = engine,
                            locale = locale,
                            voice = voice,
                            speed = speed,
                            pitch = pitch
                        )
                    )
                }

                post("api/tts") {
                    val params = call.receive<TtsParams>()
                    handleTts(params)
                }

                get("api/engines") {
                    val engines = runCatching {
                        callback.engines()
                    }.onFailure { e ->
                        callback.log(
                            Log.ERROR,
                            "api/engines error\n${e.stackTraceToString()}"
                        )
                    }.getOrElse {
                        emptyList()
                    }

                    call.respondText(
                        text = enginesToJson(engines),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }

                get("api/voices") {
                    val engine = call.parameters.getOrFail("engine")

                    val voices = runCatching {
                        callback.voices(engine)
                    }.onFailure { e ->
                        callback.log(
                            Log.ERROR,
                            "api/voices error\n${e.stackTraceToString()}"
                        )
                    }.getOrElse {
                        emptyList()
                    }

                    call.respondText(
                        text = voicesToJson(voices),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }

                get("api/legado") {
                    val api = call.parameters.getOrFail("api")
                    val name = call.parameters.getOrFail("name")
                    val engine = call.parameters.getOrFail("engine")
                    val voice = call.parameters["voice"] ?: ""
                    val pitch = call.parameters["pitch"] ?: "50"

                    call.respond(
                        LegadoUtils.getLegadoJson(api, name, engine, voice, pitch)
                    )
                }
            }
        }
    }

    override fun start(wait: Boolean, onStarted: () -> Unit, onStopped: () -> Unit) {
        ktor.application.monitor.subscribe(ApplicationStarted) {
            onStarted()
        }
        ktor.application.monitor.subscribe(ApplicationStopped) {
            onStopped()
        }
        ktor.start(wait)
    }

    override fun stop() {
        ktor.stop(100, 500)
    }

    interface Callback : BaseCallback {
        suspend fun tts(
            params: TtsParams,
        ): File?

        suspend fun voices(engine: String): List<Voice>
        suspend fun engines(): List<Engine>
    }
}