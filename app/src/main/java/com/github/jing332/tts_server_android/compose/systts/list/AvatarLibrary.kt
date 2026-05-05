package com.github.jing332.tts_server_android.compose.systts.list

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class AvatarLibraryItem(
    val gender: String = "",
    @SerialName("icon_url")
    val iconUrl: String = ""
)

private val avatarLibraryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun loadAvatarLibrary(context: Context): List<AvatarLibraryItem> {
    return runCatching {
        context.assets.open("avatar_library.json").bufferedReader().use { reader ->
            avatarLibraryJson.decodeFromString<List<AvatarLibraryItem>>(reader.readText())
        }
            .filter { it.iconUrl.isNotBlank() }
            .distinctBy { it.iconUrl }
    }.getOrElse {
        emptyList()
    }
}
