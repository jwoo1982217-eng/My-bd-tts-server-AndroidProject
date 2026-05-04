package com.github.jing332.database.entities.systts

import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.source.LocalTtsSource
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.v1.SystemTts
import com.github.jing332.database.entities.systts.v1.tts.BgmTTS
import com.github.jing332.database.entities.systts.v1.tts.LocalTTS
import com.github.jing332.database.entities.systts.v1.tts.PluginTTS

object SystemTtsMigration {
    fun v1Tov2(v1: SystemTts): SystemTtsV2? {
        val config = if (v1.tts is BgmTTS) BgmConfiguration(
            musicList = (v1.tts as BgmTTS).musicList.toList(),
            volume = v1.tts.volume / 1000f
        )
        else
            TtsConfigurationDTO(
                speechRule = v1.speechRule,
                audioParams = v1.tts.audioParams,
                audioFormat = v1.tts.audioFormat,
                source = when (v1.tts) {
                    is LocalTTS -> {
                        val tts = v1.tts as LocalTTS
                        LocalTtsSource(
                            engine = tts.engine ?: "",
                            locale = tts.locale,
                            voice = tts.voiceName ?: "",
                            speed = (tts.rate + 50) / 100f,
                            pitch = tts.pitch / 100f,
                            extraParams = tts.extraParams,
                            isDirectPlayMode = tts.isDirectPlayMode
                        )
                    }

                    is PluginTTS -> {
                        val tts = v1.tts as PluginTTS
                        PluginTtsSource(
                            pluginId = tts.pluginId,
                            locale = tts.locale,
                            voice = tts.voice,
                            speed = (tts.rate + 50) / 100f,
                            volume = (tts.volume + 50) / 100f,
                            pitch = (tts.pitch + 50) / 100f,
                            data = tts.data
                        )
                    }

                    else -> return null
                }
            )

        return SystemTtsV2(
            id = v1.id,
            displayName = v1.displayName ?: "",
            groupId = v1.groupId,
            isEnabled = v1.isEnabled,
            order = v1.order,
            config = config
        )
    }

    fun needMigrate(): Boolean {
        return dbm.systemTtsDao.allTts.isNotEmpty()
    }

    private fun clear() {
        dbm.systemTtsDao.allTts.forEach {
            dbm.systemTtsDao.deleteTts(it)
        }
    }

    fun migrate() {
        dbm.systemTtsDao.allTts.forEach {
            dbm.systemTtsV2.insert(v1Tov2(it) ?: return@forEach)
        }
        clear()
    }
}