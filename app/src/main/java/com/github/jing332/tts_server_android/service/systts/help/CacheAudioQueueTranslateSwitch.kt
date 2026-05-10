package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context

object CacheAudioQueueTranslateSwitch {
    private const val PREF_NAME = "cache_audio_queue_translate_switch"

    private fun normalizeKey(ruleName: String): String {
        return ruleName.trim().ifBlank { "unknown" }
    }

    fun isEnabled(context: Context, ruleName: String): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(normalizeKey(ruleName), false)
    }

    fun setEnabled(context: Context, ruleName: String, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(normalizeKey(ruleName), enabled)
            .apply()
    }
}
