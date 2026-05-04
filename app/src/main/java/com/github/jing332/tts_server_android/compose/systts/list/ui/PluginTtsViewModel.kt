package com.github.jing332.tts_server_android.compose.systts.list.ui

import android.app.Application
import android.content.Context
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.drake.net.utils.withIO
import com.drake.net.utils.withMain
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.PluginTtsProvider
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts_server_android.JsConsoleManager
import com.github.jing332.tts_server_android.app
import io.github.oshai.kotlinlogging.KotlinLogging

class PluginTtsViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private val logger = KotlinLogging.logger { PluginTtsViewModel::class.java.name }
    }

    lateinit var engine: TtsPluginUiEngineV2

    @Suppress("UNCHECKED_CAST")
    fun service(): TextToSpeechProvider<TextToSpeechSource> {
        return PluginTtsProvider(app, engine.plugin).also {
            it.engine = engine
        } as TextToSpeechProvider<TextToSpeechSource>
    }

    private fun initEngine(plugin: Plugin?, source: PluginTtsSource) {
        if (this::engine.isInitialized) return

        // compat preview plugin ui
        engine = if (plugin == null)
            TtsPluginEngineManager.get(app, getPluginFromDB(source.pluginId))
        else TtsPluginUiEngineV2(app, plugin).apply { eval() }

        engine.console = JsConsoleManager.ui
        engine.source = source
    }

    private fun getPluginFromDB(id: String) =
        dbm.pluginDao.getEnabled(pluginId = id)
            ?: throw IllegalStateException("Plugin $id not found from database")

    var isLoading by mutableStateOf(true)

    val locales = mutableStateListOf<Pair<String, String>>()
    val voices = mutableStateListOf<TtsPluginUiEngineV2.Voice>()

    suspend fun load(
        context: Context,
        plugin: Plugin?,
        source: PluginTtsSource,
        linearLayout: LinearLayout,
    ) =
        withIO {
            isLoading = true
            try {
                initEngine(plugin, source)
                engine.onLoadData()

                withMain {
                    engine.onLoadUI(context, linearLayout)
                }

                updateLocales()
                updateVoices(source.locale)
            } catch (t: Throwable) {
                throw t
            } finally {
                isLoading = false
            }
        }

    private suspend fun updateLocales() {
        val list = engine.getLocales().toList()
        withMain {
            locales.clear()
            locales.addAll(list)
        }
    }

    suspend fun updateVoices(locale: String) {
        val list = engine.getVoices(locale).toList()
        withMain {
            voices.clear()
            voices.addAll(list)
        }
    }

    fun updateCustomUI(locale: String, voice: String) {
        try {
            engine.onVoiceChanged(locale, voice)
        } catch (_: NoSuchMethodException) {
        }
    }
}