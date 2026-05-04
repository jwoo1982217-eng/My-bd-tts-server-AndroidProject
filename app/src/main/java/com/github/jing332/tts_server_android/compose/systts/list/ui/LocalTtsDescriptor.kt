package com.github.jing332.tts_server_android.compose.systts.list.ui

import android.content.Context
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.LocalTtsSource
import com.github.jing332.tts_server_android.R

class LocalTtsDescriptor(val context: Context, val systemTts: SystemTtsV2) :
    TtsItemDescriptor<LocalTtsSource>(systemTts.config) {

    override val type: String
        get() = context.getString(R.string.local)

    override val desc: String
        get() {
            val strFollow by lazy { context.getString(R.string.follow) }

            val rateStr =
                if (source.speed == LocalTtsSource.SPEED_FOLLOW) strFollow else source.speed
            val pitchStr =
                if (source.pitch == LocalTtsSource.PITCH_FOLLOW) strFollow else source.pitch
            val volumeStr =
                if (source.volume == LocalTtsSource.VOLUME_FOLLOW) strFollow else source.volume

            return source.voice + "<br>" + context.getString(
                R.string.systts_play_params_description,
                "<b>${rateStr}</b>",
                "<b>${volumeStr}</b>",
                "<b>${pitchStr}</b>"
            )
        }


    override val bottom: String
        get() = (systemTts.config as TtsConfigurationDTO).audioFormat.run {
            "${sampleRate}hz" + if (isNeedDecode) " | " + context.getString(R.string.decode) else ""
        }

}