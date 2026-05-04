package com.github.jing332.server

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drake.net.Net
import com.github.jing332.common.LogEntry
import com.github.jing332.server.forwarder.Engine
import com.github.jing332.server.forwarder.SystemTtsForwardServer
import com.github.jing332.server.forwarder.TtsParams
import com.github.jing332.server.forwarder.Voice
import com.github.jing332.server.script.ScriptRemoteServer
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.github.jing332.server.test", appContext.packageName)
    }

    @Test
    fun forwarder() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val server = SystemTtsForwardServer(
            1233,
            object : SystemTtsForwardServer.Callback {
                override fun log(level: Int, message: String) {
                    Log.println(level, "ServerTest", message)
                }

                override suspend fun tts(params: TtsParams): File? {
                    return runCatching {
                        val file = File(appContext.cacheDir, "sample-3s.wav")

                        val resp: Response =
                            Net.get("https://download.samplelib.com/wav/sample-3s.wav")
                                .execute()

                        resp.body!!.byteStream().use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        file
                    }.getOrNull()
                }

                override suspend fun voices(engine: String): List<Voice> {
                    return listOf(
                        Voice(
                            name = "Xiao Yi",
                            locale = "zh-CN",
                            localeName = "中文"
                        )
                    )
                }

                override suspend fun engines(): List<Engine> {
                    return listOf(
                        Engine(
                            name = "Huawei",
                            label = "com.huawei.tts"
                        ),
                        Engine(
                            name = "Google",
                            label = "com.google.tts"
                        )
                    )
                }
            }
        )

        server.start(true)
    }

    @Test
    fun scriptRemote() {
        val server = ScriptRemoteServer(
            4566,
            object : ScriptRemoteServer.Callback {
                override fun pull(): String {
                    return """function test(){ return "hello, I am TTS Server." }"""
                }

                override fun push(code: String) {
                    println("push update code: $code")
                }

                override fun action(name: String) {
                    println("action: $name")
                }

                override fun log(): List<LogEntry> {
                    return listOf(
                        LogEntry(level = Log.DEBUG, message = "debug..."),
                        LogEntry(level = Log.VERBOSE, message = "verbose..."),
                        LogEntry(level = Log.INFO, message = "info..."),
                        LogEntry(level = Log.ERROR, message = "error..."),
                        LogEntry(level = Log.WARN, message = "warn..."),
                    )
                }
            }
        )

        server.start(wait = true)
    }
}