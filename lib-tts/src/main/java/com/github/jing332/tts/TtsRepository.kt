package com.github.jing332.tts

import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.BgmConfiguration
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.synthesizer.ITtsRepository
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts.synthesizer.TtsConfiguration.Companion.toVO

private const val KEY_PLUGIN_AUDIO_SPEED = "plugin_audio_speed"
private const val KEY_PLUGIN_AUDIO_VOLUME = "plugin_audio_volume"
private const val KEY_PLUGIN_AUDIO_PITCH = "plugin_audio_pitch"

private fun neutralAudioParams(): AudioParams {
    return AudioParams(
        speed = 1f,
        volume = 1f,
        pitch = 1f
    )
}

private fun Float.asAudioMultiplier(): Float {
    return if (this <= 0f) 1f else this
}

private fun multiplyAudioParams(
    total: AudioParams,
    plugin: AudioParams,
    group: AudioParams,
    tts: AudioParams,
): AudioParams {
    return AudioParams(
        speed = total.speed.asAudioMultiplier() *
                plugin.speed.asAudioMultiplier() *
                group.speed.asAudioMultiplier() *
                tts.speed.asAudioMultiplier(),

        volume = total.volume.asAudioMultiplier() *
                plugin.volume.asAudioMultiplier() *
                group.volume.asAudioMultiplier() *
                tts.volume.asAudioMultiplier(),

        pitch = total.pitch.asAudioMultiplier() *
                plugin.pitch.asAudioMultiplier() *
                group.pitch.asAudioMultiplier() *
                tts.pitch.asAudioMultiplier()
    )
}

private fun audioParamsFromPluginVars(vars: Map<String, String>?): AudioParams {
    if (vars == null) return neutralAudioParams()

    return AudioParams(
        speed = vars[KEY_PLUGIN_AUDIO_SPEED]?.toFloatOrNull() ?: 1f,
        volume = vars[KEY_PLUGIN_AUDIO_VOLUME]?.toFloatOrNull() ?: 1f,
        pitch = vars[KEY_PLUGIN_AUDIO_PITCH]?.toFloatOrNull() ?: 1f
    )
}

internal class TtsRepository(
    val context: SynthesizerContext,
) : ITtsRepository {

    override fun init() {
    }

    override fun destroy() {
    }

    override fun getTts(id: Long): TtsConfiguration? {
        val systts = dbm.systemTtsV2.get(id)
        val config = systts.config as? TtsConfigurationDTO ?: return null

        val totalParams = context.cfg.audioParams()

        val groupParams = try {
            dbm.systemTtsV2.getGroup(systts.groupId)?.audioParams ?: neutralAudioParams()
        } catch (_: Throwable) {
            neutralAudioParams()
        }

        val pluginParams = pluginAudioParams(config.source)

        val finalParams = multiplyAudioParams(
            total = totalParams,
            plugin = pluginParams,
            group = groupParams,
            tts = config.audioParams
        )

        return config.toVO().copy(
            audioParams = finalParams,
            tag = systts
        )
    }

    override fun getAllTts(): Map<Long, TtsConfiguration> {
        val totalParams = context.cfg.audioParams()
        val groupWithTts = dbm.systemTtsV2.getAllGroupWithTts()

        val pluginAudioParamsMap = try {
            dbm.pluginDao.all.associate { plugin ->
                plugin.pluginId to audioParamsFromPluginVars(plugin.userVars)
            }
        } catch (_: Throwable) {
            emptyMap()
        }

        fun pluginParamsOf(source: Any?): AudioParams {
            val pluginSource = source as? PluginTtsSource ?: return neutralAudioParams()
            return pluginAudioParamsMap[pluginSource.pluginId] ?: neutralAudioParams()
        }

        fun finalAudioParams(
            groupParams: AudioParams,
            config: TtsConfigurationDTO,
        ): AudioParams {
            return multiplyAudioParams(
                total = totalParams,
                plugin = pluginParamsOf(config.source),
                group = groupParams,
                tts = config.audioParams
            )
        }

        val map = linkedMapOf<Long, TtsConfiguration>()

        val standbyConfigs =
            groupWithTts.flatMap { group ->
                group.list
                    .filter {
                        it.isEnabled &&
                                (it.config as? TtsConfigurationDTO)?.speechRule?.isStandby == true
                    }
                    .mapNotNull { systts ->
                        val config = systts.config as? TtsConfigurationDTO ?: return@mapNotNull null

                        config.toVO().copy(
                            audioParams = finalAudioParams(
                                groupParams = group.group.audioParams,
                                config = config
                            ),
                            tag = systts
                        )
                    }
            }

        for (group in groupWithTts) {
            val groupParams = group.group.audioParams

            for (tts in group.list.sortedBy { it.order }) {
                if (!tts.isEnabled) continue

                val config = tts.config
                if (config !is TtsConfigurationDTO) continue

                val standby = standbyConfigs.find {
                    it.speechInfo.target == config.speechRule.target &&
                            it.speechInfo.tagRuleId == config.speechRule.tagRuleId &&
                            it.speechInfo.tagName == config.speechRule.tagName
                }

                map[tts.id] = TtsConfiguration(
                    speechInfo = config.speechRule,
                    audioParams = finalAudioParams(
                        groupParams = groupParams,
                        config = config
                    ),
                    audioFormat = config.audioFormat,
                    source = config.source,
                    standbyConfig = standby,
                    tag = tts
                )
            }
        }

        return map
    }

    override fun getAllBgm(): List<BgmConfiguration> {
        return dbm.systemTtsV2.allEnabled
            .map { it.config }
            .filterIsInstance<BgmConfiguration>()
    }

    private fun pluginAudioParams(source: Any?): AudioParams {
        val pluginSource = source as? PluginTtsSource ?: return neutralAudioParams()

        val plugin = try {
            dbm.pluginDao.all.firstOrNull {
                it.pluginId == pluginSource.pluginId
            }
        } catch (_: Throwable) {
            null
        }

        return audioParamsFromPluginVars(plugin?.userVars)
    }
}