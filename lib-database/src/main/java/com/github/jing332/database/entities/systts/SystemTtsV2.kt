package com.github.jing332.database.entities.systts

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Parcelize
@Serializable
@Entity(tableName = "system_tts_v2")
@TypeConverters(SystemTtsV2.Converters::class)
data class SystemTtsV2(
    @PrimaryKey(autoGenerate = false)
    var id: Long = System.currentTimeMillis(),
    var displayName: String = "",
    var groupId: Long = 0,
    var isEnabled: Boolean = false,
    var order: Int = 0,

    var config: IConfiguration = EmptyConfiguration,
) : Parcelable {
    val ttsConfig: TtsConfigurationDTO
        get() = config as TtsConfigurationDTO


    @Suppress("unused")
    class Converters {
        companion object {
            lateinit var json: Json
            var defaultConfig: IConfiguration = EmptyConfiguration
        }

        @TypeConverter
        fun source2String(source: IConfiguration): String {

            return json.encodeToString(source)
        }

        @TypeConverter
        fun string2Source(s: String): IConfiguration {
            return try {
                json.decodeFromString(s)
            } catch (e: SerializationException) {
                defaultConfig
            }

        }
    }
}