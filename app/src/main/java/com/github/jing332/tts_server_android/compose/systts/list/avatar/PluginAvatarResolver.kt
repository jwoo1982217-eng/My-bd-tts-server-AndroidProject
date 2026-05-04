package com.github.jing332.tts_server_android.compose.systts.list.avatar

import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource

fun resolvePluginAvatarUrl(tts: SystemTtsV2): String? {
    val source = (tts.config as? TtsConfigurationDTO)?.source as? PluginTtsSource

    val fromData = listOf(
        "avatarUrl",
        "avatar",
        "iconUrl",
        "icon",
        "imageUrl",
        "image",
        "coverUrl",
        "cover",
        "photo",
        "pic",
        "portrait",
        "face"
    ).firstNotNullOfOrNull { key ->
        source?.data?.get(key)?.trim()?.takeIf { isUsableAvatarUrl(it) }
    }

    if (!fromData.isNullOrBlank()) return fromData

    val raw = listOf(
        tts.displayName,
        tts.config.toString()
    ).joinToString("\n")

    val regex = Regex(
        pattern = """(?i)(avatarUrl|avatar|headUrl|head|iconUrl|icon|imageUrl|image|coverUrl|cover|photo|pic|portrait|face)\s*[=:]\s*['"]?([^'",\s)}]+)"""
    )

    return regex.find(raw)
        ?.groupValues
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf { isUsableAvatarUrl(it) }
}

private fun isUsableAvatarUrl(value: String): Boolean {
    return value.startsWith("http://") ||
            value.startsWith("https://") ||
            value.startsWith("file://") ||
            value.startsWith("content://") ||
            value.startsWith("/")
}
