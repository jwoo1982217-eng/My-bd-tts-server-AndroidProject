package com.github.jing332.tts_server_android.compose.systts.plugin

import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts_server_android.App
import java.io.File

class PluginManager(private val plugin: Plugin) {
    private val pluginDataDir = File(
        App.context.getExternalFilesDir(null),
        "plugins/${plugin.pluginId}"
    )

    fun hasCache(): Boolean {
        pluginDataDir.mkdirs()

        return try {
            pluginDataDir.list()?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }

    fun clearCache() {
        try {
            pluginDataDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }
}