package com.github.jing332.database.entities

import androidx.room.TypeConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MapConverters {
    @OptIn(ExperimentalSerializationApi::class)
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
            explicitNulls = false
        }
    }

    @TypeConverter
    fun toMap(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(s)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromMap(tags: Map<String, String>): String {
        return try {
            json.encodeToString(tags)
        } catch (_: Exception) {
            "{}"
        }
    }

    @TypeConverter
    fun toNestMap(s: String): Map<String, Map<String, String>> {
        if (s.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Map<String, String>>>(s)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromNestMap(map: Map<String, Map<String, String>>): String {
        return try {
            json.encodeToString(map)
        } catch (_: Exception) {
            "{}"
        }
    }

    @TypeConverter
    fun toMapList(s: String): Map<String, List<Map<String, String>>> {
        if (s.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, List<Map<String, String>>>>(s)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromMapList(tags: Map<String, List<Map<String, String>>>): String {
        return try {
            json.encodeToString(tags)
        } catch (_: Exception) {
            "{}"
        }
    }
}
