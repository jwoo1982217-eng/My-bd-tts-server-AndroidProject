package com.github.jing332.tts_server_android.compose.systts.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts_server_android.R

private data class PluginSelectGroup(
    val key: String,
    val name: String,
    val plugins: List<Plugin>,
)

private fun defaultPluginSelectGroupName(plugin: Plugin): String {
    val text = "${plugin.name} ${plugin.pluginId}".lowercase()

    return when {
        "呱呱" in text -> "呱呱"
        "mimo" in text -> "MIMO"
        "角色管理" in text -> "角色管理"
        else -> plugin.pluginGroupName.ifBlank {
            plugin.name.trim().ifBlank {
                plugin.pluginId.trim().ifBlank { "其它插件" }
            }
        }
    }
}

private fun safePluginSelectGroupId(name: String): String {
    return name
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_\\-.\\u4e00-\\u9fa5]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "plugin_group" }
}

private fun List<Plugin>.toPluginSelectGroups(): List<PluginSelectGroup> {
    return groupBy { plugin ->
        plugin.pluginGroupId.ifBlank {
            safePluginSelectGroupId(
                plugin.pluginGroupName.ifBlank {
                    defaultPluginSelectGroupName(plugin)
                }
            )
        }
    }.map { (key, plugins) ->
        val sortedPlugins = plugins.sortedWith(
            compareBy<Plugin> { it.name }
                .thenByDescending { it.version }
                .thenBy { it.pluginId }
        )

        val first = sortedPlugins.first()

        PluginSelectGroup(
            key = key,
            name = first.pluginGroupName.ifBlank { defaultPluginSelectGroupName(first) },
            plugins = sortedPlugins
        )
    }.sortedBy { it.name }
}

@Composable
fun PluginSelectionDialog(onDismissRequest: () -> Unit, onSelect: (Plugin) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(id = R.string.select_plugin)) },
        text = {
            val plugins = dbm.pluginDao.allEnabled

            if (plugins.isEmpty()) {
                Text(
                    stringResource(id = R.string.no_plugins),
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val groups = remember(plugins) { plugins.toPluginSelectGroups() }
                val expandedGroupKeys = remember(groups) {
                    mutableStateOf(emptySet<String>())
                }

                LazyColumn {
                    groups.forEach { group ->
                        val expanded = expandedGroupKeys.value.contains(group.key)

                        item(key = "group_${group.key}") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple()
                                    ) {
                                        expandedGroupKeys.value =
                                            if (expanded) {
                                                expandedGroupKeys.value - group.key
                                            } else {
                                                expandedGroupKeys.value + group.key
                                            }
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (expanded) "▼ 🗂️" else "▶ 🗂️",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                        if (expanded) {
                            items(group.plugins, key = { it.id }) { plugin ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .padding(start = 18.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple()
                                        ) {
                                            onSelect(plugin)
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    PluginImage(model = plugin.iconUrl, name = plugin.name)

                                    Column {
                                        Text(
                                            text = plugin.name,
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            style = MaterialTheme.typography.titleMedium
                                        )

                                        Text(
                                            text = plugin.pluginId,
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(id = R.string.cancel))
            }
        }
    )
}
