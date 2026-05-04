package com.github.jing332.tts_server_android.compose.systts.list.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.jing332.common.utils.toast
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.EmptyConfiguration
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.LocalTtsSource
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts_server_android.AppLocale
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.systts.list.ui.ConfigUiFactory
import com.github.jing332.tts_server_android.compose.systts.plugin.PluginSelectionDialog
import com.github.jing332.tts_server_android.toCode
import kotlinx.coroutines.launch

@Composable
fun PluginSwitcherBlock(
    systts: SystemTtsV2,
    onSysttsChange: (SystemTtsV2) -> Unit,
) {
    val context = LocalContext.current
    val config = systts.config as? TtsConfigurationDTO ?: return
    val source = config.source as? PluginTtsSource ?: return

    var showPluginDialog by remember { mutableStateOf(false) }
    var pendingPlugin by remember { mutableStateOf<Plugin?>(null) }

    /*
     * 不要在 PluginSelectionDialog 的选择回调里直接切换 systts。
     * 有些选择弹窗内部会使用 coroutine scope 关闭弹窗；如果这里立刻重建插件编辑 UI，
     * 旧弹窗的 scope 会离开 composition，容易抛出 LeftCompositionCancellationException。
     *
     * 所以这里先关闭弹窗并记录待切换插件，再由 LaunchedEffect 在弹窗退出后执行真正切换。
     */
    LaunchedEffect(pendingPlugin) {
        val plugin = pendingPlugin ?: return@LaunchedEffect
        pendingPlugin = null

        val currentConfig = systts.config as? TtsConfigurationDTO ?: return@LaunchedEffect
        val currentSource = currentConfig.source as? PluginTtsSource ?: return@LaunchedEffect

        val newSource = currentSource.copy(
            pluginId = plugin.pluginId,
            locale = AppLocale.current(context).toCode(),
            voice = "",
            data = mutableMapOf()
        )

        onSysttsChange(
            systts.copy(
                config = currentConfig.copy(
                    source = newSource
                )
            )
        )
    }

    if (showPluginDialog) {
        PluginSelectionDialog(
            onDismissRequest = {
                showPluginDialog = false
            }
        ) { plugin ->
            showPluginDialog = false
            pendingPlugin = plugin
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = "插件 / 引擎插件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                showPluginDialog = true
            }
        ) {
            Text(
                text = if (source.pluginId.isBlank()) {
                    "请选择插件"
                } else {
                    "当前：${source.plugin?.name ?: source.pluginId}    更换插件"
                }
            )
        }

        Text(
            text = "切换插件后会保留分组、显示名称、朗读规则和音频参数；声音、语言和插件附加参数会重置。切换后请重新选择声音并保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun editorFactoryKey(systts: SystemTtsV2): String {
    val config = systts.config as? TtsConfigurationDTO
    val source = config?.source as? PluginTtsSource

    return if (source == null) {
        systts.config::class.java.name
    } else {
        listOf(
            systts.config::class.java.name,
            source.pluginId,
            source.locale,
            source.voice,
            source.data.hashCode().toString()
        ).joinToString("|")
    }
}

@Composable
fun TtsEditContainerScreen(
    modifier: Modifier,
    systts: SystemTtsV2,
    onSysttsChange: (SystemTtsV2) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    /*
     * 这里不要用 key(...) 包住整个 FullEditScreen。
     * key 强制销毁旧 composition 时，部分插件编辑 UI 内部正在运行的协程会被取消，
     * 容易被上层错误弹窗捕获成 “The coroutine scope left the composition”。
     *
     * 只让 ConfigUiFactory 按 pluginId/voice/data 重新创建即可，避免假切换，同时减少协程取消错误。
     */
    val factoryKey = editorFactoryKey(systts)
    val ui = remember(factoryKey) { ConfigUiFactory.from(systts.config) }
    val context = LocalContext.current

    if (ui == null || systts.config == EmptyConfiguration) {
        LaunchedEffect(ui) {
            context.toast(R.string.cannot_empty)
            onCancel()
        }
        return
    }

    val callbacks = rememberSaveCallBacks()
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalSaveCallBack provides callbacks) {
        ui.FullEditScreen(
            modifier = modifier,
            systemTts = systts,
            content = {
                Column {
                    SpeechRuleEditScreen(
                        Modifier.padding(8.dp),
                        systts,
                        onSysttsChange = onSysttsChange
                    )
                }
            },
            onSystemTtsChange = onSysttsChange,
            onSave = {
                scope.launch {
                    for (callBack in callbacks) {
                        if (!callBack.onSave()) return@launch
                    }

                    onSave()
                }
            },
            onCancel = onCancel
        )
    }
}

@Preview
@Composable
private fun PreviewContainer() {
    var systts by remember {
        mutableStateOf(
            SystemTtsV2(
                config = TtsConfigurationDTO(
                    source = LocalTtsSource(engine = "")
                )
            )
        )
    }

    TtsEditContainerScreen(
        modifier = Modifier.fillMaxSize(),
        systts = systts,
        onSysttsChange = {
            systts = it
        },
        onSave = {

        },
        onCancel = {

        }
    )
}
