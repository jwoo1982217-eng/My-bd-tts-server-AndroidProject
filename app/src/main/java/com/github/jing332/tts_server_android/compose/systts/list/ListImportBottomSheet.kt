package com.github.jing332.tts_server_android.compose.systts.list

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.GroupWithSystemTts
import com.github.jing332.database.entities.systts.SystemTtsGroup
import com.github.jing332.database.entities.systts.SystemTtsMigration
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.v1.GroupWithV1TTS
import com.github.jing332.tts_server_android.compose.systts.ConfigImportBottomSheet
import com.github.jing332.tts_server_android.compose.systts.ConfigModel
import com.github.jing332.tts_server_android.compose.systts.SelectImportConfigDialog
import com.github.jing332.tts_server_android.constant.AppConst

private const val PLUGIN_REDIRECT_PREFS = "plugin_id_redirect_map"

private fun readPluginRedirectMap(context: Context): Map<String, String> {
    val prefs = context.getSharedPreferences(
        PLUGIN_REDIRECT_PREFS,
        Context.MODE_PRIVATE
    )

    return prefs.all
        .mapNotNull { entry ->
            val value = entry.value as? String ?: return@mapNotNull null
            entry.key to value
        }
        .toMap()
}

private fun SystemTtsV2.applyPluginRedirect(
    pluginRedirectMap: Map<String, String>,
): SystemTtsV2 {
    val ttsConfig = config as? TtsConfigurationDTO ?: return this
    val pluginSource = ttsConfig.source as? PluginTtsSource ?: return this

    val newPluginId = pluginRedirectMap[pluginSource.pluginId]

    if (newPluginId.isNullOrBlank() || newPluginId == pluginSource.pluginId) {
        return this
    }

    return copy(
        config = ttsConfig.copy(
            source = pluginSource.copy(
                pluginId = newPluginId
            )
        )
    )
}

@Composable
fun ListImportBottomSheet(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    var selectDialog by remember { mutableStateOf<List<ConfigModel>?>(null) }

    if (selectDialog != null) {
        SelectImportConfigDialog(
            onDismissRequest = {
                selectDialog = null
            },
            models = selectDialog!!,
            onSelectedList = { list ->
                val pluginRedirectMap = readPluginRedirectMap(context)

                list.map {
                    @Suppress("UNCHECKED_CAST")
                    it as Pair<SystemTtsGroup, SystemTtsV2>
                }
                    .forEach {
                        val group = it.first
                        val tts = it.second.applyPluginRedirect(pluginRedirectMap)

                        dbm.systemTtsV2.insertGroup(group)
                        dbm.systemTtsV2.insert(tts)
                    }

                list.size
            }
        )
    }

    ConfigImportBottomSheet(
        onDismissRequest = onDismissRequest,
        onImport = { json ->
            val allList = mutableListOf<ConfigModel>()

            getImportList(json, false)?.forEach { groupWithTts ->
                val group = groupWithTts.group

                groupWithTts.list.forEach { sysTts ->
                    allList.add(
                        ConfigModel(
                            true,
                            sysTts.displayName.toString(),
                            group.name,
                            group to sysTts
                        )
                    )
                }
            }

            selectDialog = allList
        }
    )
}

private fun getImportList(
    json: String,
    fromLegado: Boolean,
): List<GroupWithSystemTts>? {
    val groupName = StringUtils.formattedDate()
    val groupId = System.currentTimeMillis()
    val groupCount = dbm.systemTtsV2.groupCount

    if (fromLegado) {
        /*AppConst.jsonBuilder.decodeFromString<List<LegadoHttpTts>>(json).ifEmpty { return null }
            .let { list ->
                return listOf(GroupWithSystemTts(
                    group = SystemTtsGroup(
                        id = groupId,
                        name = groupName,
                        order = groupCount
                    ),
                    list = list.map {
                        SystemTtsV2(
                            groupId = groupId,
                            id = it.id,
                            displayName = it.name,
                        )
                    }

                ))
            }*/
        return null
    } else {
        return if (json.contains("\"group\"")) {
            if (json.contains("\"config\"") && json.contains("\"source\"")) {
                AppConst.jsonBuilder.decodeFromString<List<GroupWithSystemTts>>(json)
            } else {
                val old = AppConst.jsonBuilder.decodeFromString<List<GroupWithV1TTS>>(json)

                old.map {
                    GroupWithSystemTts(
                        it.group,
                        it.list.map { tts ->
                            SystemTtsMigration.v1Tov2(tts)
                        }.filterNotNull()
                    )
                }
            }
        } else {
            listOf()
        }
    }
}