package com.github.jing332.tts_server_android.compose.systts.list.ui

import com.github.jing332.tts_server_android.compose.systts.list.avatar.resolveVoiceAvatarUri
import android.util.Log
import android.widget.LinearLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drake.net.utils.withIO
import com.github.jing332.common.utils.toScale
import com.github.jing332.common.utils.toast
import com.github.jing332.compose.widgets.AppSpinner
import com.github.jing332.compose.widgets.LabelSlider
import com.github.jing332.compose.widgets.LoadingContent
import com.github.jing332.compose.widgets.LoadingDialog
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.BasicAudioFormat
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.systts.AuditionDialog
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.AuditionTextField
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.BasicInfoEditScreen
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.SaveActionHandler
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


private fun isVoiceOptionalPlugin(
    pluginId: String,
    pluginName: String,
    locale: String,
): Boolean {
    val id = pluginId.trim()
    val name = pluginName.trim()
    val loc = locale.trim()

    return id == "mingwuyan" ||
            id.contains("mingwuyan", ignoreCase = true) ||
            name.contains("角色管理") ||
            name.contains("Vivi", ignoreCase = true) ||
            name.contains("变身") ||
            loc.contains("vivi", ignoreCase = true) ||
            loc.contains("变身")
}

class PluginTtsUI : IConfigUI() {
    companion object {
        const val TAG = "PluginTtsUI"
    }

    @Composable
    override fun ParamsEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
    ) {
        val tts = (systemTts.config as TtsConfigurationDTO).source as PluginTtsSource

        Column(modifier) {
            val rateStr =
                stringResource(
                    id = R.string.label_speech_rate,
                    if (tts.speed == 0f) stringResource(id = R.string.follow) else tts.speed.toString()
                )
            LabelSlider(
                text = rateStr,
                value = tts.speed,
                onValueChange = {
                    onSystemTtsChange(systemTts.copySource(tts.copy(speed = it.toScale(2))))
                },
                valueRange = 0f..3f
            )

            val volumeStr =
                stringResource(
                    id = R.string.label_speech_volume,
                    if (tts.volume == 0f) stringResource(id = R.string.follow) else tts.volume.toString()
                )
            LabelSlider(
                text = volumeStr,
                value = tts.volume,
                onValueChange = {
                    onSystemTtsChange(
                        systemTts.copySource(
                            tts.copy(volume = it.toScale(2))
                        )
                    )
                },
                valueRange = 0f..3f
            )

            val pitchStr = stringResource(
                id = R.string.label_speech_pitch,
                if (tts.pitch == 0f) stringResource(id = R.string.follow) else tts.pitch.toString()
            )
            LabelSlider(
                text = pitchStr,
                value = tts.pitch,
                onValueChange = {
                    onSystemTtsChange(
                        systemTts.copySource(
                            tts.copy(pitch = it.toScale(2))
                        )
                    )
                },
                valueRange = 0f..3f
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun FullEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        DefaultFullEditScreen(
            modifier,
            title = stringResource(id = R.string.edit_plugin_tts),
            onCancel = onCancel,
            onSave = onSave,
        ) {
            content()

            EditContentScreen(
                systts = systemTts,
                onSysttsChange = onSystemTtsChange,
            )
        }
    }

    @Composable
    fun EditContentScreen(
        modifier: Modifier = Modifier,
        systts: SystemTtsV2,
        onSysttsChange: (SystemTtsV2) -> Unit,
        showBasicInfo: Boolean = true,
        plugin: Plugin? = null,
        vm: PluginTtsViewModel? = null,
    ) {
        var displayName by remember { mutableStateOf("") }

        @Suppress("NAME_SHADOWING")
        val systts by rememberUpdatedState(newValue = systts)
        val tts by rememberUpdatedState(
            newValue = (systts.config as TtsConfigurationDTO).source as PluginTtsSource
        )

        val initialSource = (systts.config as TtsConfigurationDTO).source as PluginTtsSource
        val pluginKey = plugin?.pluginId ?: initialSource.pluginId

        val realVm: PluginTtsViewModel = vm ?: viewModel(key = "PluginTtsUI_$pluginKey")

        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val currentPlugin = remember(plugin?.id, tts.pluginId) {
            plugin
                ?: tts.plugin
                ?: dbm.pluginDao.getEnabled(tts.pluginId)
                ?: dbm.pluginDao.getByPluginId(tts.pluginId)
                ?: dbm.pluginDao.allEnabled.firstOrNull()
                ?: dbm.pluginDao.all.firstOrNull()
        }

        LaunchedEffect(currentPlugin?.id, tts.pluginId) {
            if (currentPlugin != null && currentPlugin.pluginId != tts.pluginId) {
                onSysttsChange(
                    systts.copySource(
                        tts.copy(
                            pluginId = currentPlugin.pluginId,
                            locale = "",
                            voice = ""
                        )
                    )
                )
            }
        }

        SaveActionHandler {

            val hasStandardVoiceList = realVm.voices.isNotEmpty()
            val saveTts = if (tts.voice.isBlank() && hasStandardVoiceList) {
                tts.copy(voice = tts.voice.ifBlank { realVm.voices.firstOrNull()?.id.orEmpty() })
            } else {
                tts
            }

            if (currentPlugin == null) {
                context.toast("原插件不存在，请先更换插件并重新选择声音")
                false
            } else if (realVm.isLoading) {
                context.toast("插件还没有加载完成，请稍后再保存")
                false
            } else if (hasStandardVoiceList && saveTts.voice.isBlank()) {
                context.toast("请先选择声音")
                false
            } else {
                val sampleRate = try {
                    withIO {
                        if (saveTts.voice.isBlank()) {
                            24000
                        } else {
                            realVm.engine.getSampleRate(saveTts.locale, saveTts.voice) ?: 16000
                        }
                    }
                } catch (_: UninitializedPropertyAccessException) {
                    context.toast("插件还没有加载完成，请稍后再保存")
                    null
                } catch (e: Exception) {
                    context.displayErrorDialog(
                        e,
                        context.getString(R.string.plugin_tts_get_sample_rate_failed)
                    )
                    null
                }

                val isNeedDecode = try {
                    withIO {
                        if (saveTts.voice.isBlank()) {
                            false
                        } else {
                            realVm.engine.isNeedDecode(saveTts.locale, saveTts.voice)
                        }
                    }
                } catch (_: UninitializedPropertyAccessException) {
                    context.toast("插件还没有加载完成，请稍后再保存")
                    null
                } catch (e: Exception) {
                    context.displayErrorDialog(
                        e,
                        context.getString(R.string.plugin_tts_get_need_decode_failed)
                    )
                    null
                }

                if (sampleRate != null && isNeedDecode != null) {
                    val currentVoiceItem = realVm.voices.firstOrNull { it.id == saveTts.voice }

                    val saveVoiceName = currentVoiceItem?.name
                        ?: tts.data["voiceName"]
                        ?: systts.displayName.orEmpty()

                    val saveAvatarUrl = resolveVoiceAvatarUri(
                        packageName = context.packageName,
                        voiceName = saveVoiceName,
                        voiceId = saveTts.voice,
                        pluginIcon = currentVoiceItem?.icon
                            ?: tts.data["avatarUrl"]
                            ?: tts.data["icon"]
                    )

                    val newData = tts.data.toMutableMap().apply {
                        if (saveVoiceName.isNotBlank()) {
                            put("voiceName", saveVoiceName)
                        }

                        if (saveAvatarUrl.isNotBlank()) {
                            put("avatarUrl", saveAvatarUrl)
                            put("icon", saveAvatarUrl)
                        }
                    }.toMap()

                    onSysttsChange(
                        systts.copy(
                            displayName = if (systts.displayName.isNullOrBlank()) {
                                displayName.ifBlank { saveVoiceName }
                            } else {
                                systts.displayName
                            },
                            config = (systts.config as TtsConfigurationDTO).copy(
                                source = saveTts.copy(
                                    data = newData
                                ),
                                audioFormat = BasicAudioFormat(
                                    sampleRate = sampleRate,
                                    isNeedDecode = isNeedDecode
                                )
                            ),
                        )
                    )

                    true
                } else {
                    false
                }
            }
        }

        var showLoadingDialog by remember { mutableStateOf(false) }
        if (showLoadingDialog) {
            LoadingDialog(onDismissRequest = { showLoadingDialog = false })
        }

        var showAuditionDialog by remember { mutableStateOf(false) }
        if (showAuditionDialog) {
            AuditionDialog(
                systts = systts,
                engine = if (currentPlugin == null) null else realVm.service()
            ) {
                showAuditionDialog = false
            }
        }

        Column(modifier) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (showBasicInfo) {
                    BasicInfoEditScreen(
                        Modifier.fillMaxWidth(),
                        systemTts = systts,
                        onSystemTtsChange = onSysttsChange
                    )
                }

                AuditionTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    onAudition = {
                        val hasStandardVoiceList = realVm.voices.isNotEmpty()
                        val auditionTts = if (tts.voice.isBlank() && hasStandardVoiceList) {
                            tts.copy(voice = tts.voice.ifBlank { realVm.voices.firstOrNull()?.id.orEmpty() })
                        } else {
                            tts
                        }

                        if (currentPlugin == null) {
                            context.toast("原插件不存在，请先更换插件并重新选择声音")
                        } else if (realVm.isLoading) {
                            context.toast("插件还没有加载完成，请稍后再试听")
                        } else if (hasStandardVoiceList && auditionTts.voice.isBlank()) {
                            context.toast("请先选择声音")
                        } else {
                            if (tts.voice.isBlank() && auditionTts.voice.isNotBlank()) {
                                onSysttsChange(systts.copySource(auditionTts))
                            }
                            showAuditionDialog = true
                        }
                    }
                )


                if (currentPlugin == null) {
                    Text(
                        text = "⚠️ 没有可用插件。请先安装或启用插件。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LoadingContent(isLoading = realVm.isLoading) {
                        Column {
                            AppSpinner(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                labelText = stringResource(R.string.language),
                                value = tts.locale,
                                values = realVm.locales.map { it.first },
                                entries = realVm.locales.map { it.second },
                                onSelectedChange = { locale, _ ->
                                    val selectedLocale = locale.toString()

                                    Log.d(TAG, "locale onSelectedChange: $selectedLocale")

                                    if (selectedLocale.isBlank()) return@AppSpinner
                                    if (selectedLocale == tts.locale) return@AppSpinner

                                    onSysttsChange(
                                        systts.copySource(
                                            tts.copy(
                                                locale = selectedLocale,
                                                voice = ""
                                            )
                                        )
                                    )

                                    runCatching {
                                        scope.launch(Dispatchers.IO) {
                                            realVm.updateVoices(selectedLocale)
                                        }
                                    }
                                },
                            )

                            var allowVoiceSpinnerWriteBack by remember(
                                currentPlugin.pluginId,
                                tts.locale,
                                realVm.voices.size
                            ) {
                                mutableStateOf(false)
                            }

                            LaunchedEffect(
                                currentPlugin.pluginId,
                                tts.locale,
                                realVm.voices.size
                            ) {
                                allowVoiceSpinnerWriteBack = false
                                delay(500)
                                allowVoiceSpinnerWriteBack = true
                            }

                            val savedVoiceId = tts.voice
                            val savedDisplayName = systts.displayName.orEmpty()
                            val savedVoiceName = savedDisplayName.ifBlank {
                                tts.data["voiceName"]
                                    ?: realVm.voices.firstOrNull { it.id == savedVoiceId }?.name
                                    ?: savedVoiceId
                            }

                            val voiceValues = mutableListOf<String>().apply {
                                if (savedVoiceId.isNotBlank()) add(savedVoiceId)
                                addAll(realVm.voices.map { it.id })
                            }.distinct()

                            val voiceEntries = voiceValues.map { voiceId ->
                                if (voiceId == savedVoiceId) {
                                    savedVoiceName
                                } else {
                                    realVm.voices.firstOrNull { it.id == voiceId }?.name
                                        ?: voiceId
                                }
                            }

                            val voiceIcons = voiceValues.mapIndexed { index, voiceId ->
                                val voiceNameForAvatar = voiceEntries.getOrNull(index).orEmpty()
                                val pluginIcon =
                                    if (voiceId == savedVoiceId) {
                                        tts.data["avatarUrl"]
                                            ?: tts.data["icon"]
                                            ?: realVm.voices.firstOrNull { it.id == voiceId }?.icon
                                    } else {
                                        realVm.voices.firstOrNull { it.id == voiceId }?.icon
                                    }

                                resolveVoiceAvatarUri(
                                    packageName = context.packageName,
                                    voiceName = voiceNameForAvatar,
                                    voiceId = voiceId,
                                    pluginIcon = pluginIcon
                                )
                            }

                            AppSpinner(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                labelText = stringResource(R.string.label_voice),
                                autoSelectFirstWhenValueMissing = false,
                value = savedVoiceId,
                                values = voiceValues,
                                entries = voiceEntries,
                                icons = voiceIcons,
                                onSelectedChange = { voice, name ->
                                    val selectedVoice = voice as? String ?: return@AppSpinner

                                    if (!allowVoiceSpinnerWriteBack) {
                                        Log.d(TAG, "ignore initial voice spinner change: $selectedVoice")
                                        return@AppSpinner
                                    }

                                    if (selectedVoice == tts.voice) return@AppSpinner

                                    val selectedVoiceItem = realVm.voices.firstOrNull { it.id == selectedVoice }
                                    val selectedName = selectedVoiceItem?.name ?: name.toString()

                                    val selectedAvatar = resolveVoiceAvatarUri(
                                        packageName = context.packageName,
                                        voiceName = selectedName,
                                        voiceId = selectedVoice,
                                        pluginIcon = selectedVoiceItem?.icon
                                    )

                                    val lastName = savedVoiceName

                                    val newData = tts.data.toMutableMap().apply {
                                        put("voiceName", selectedName)
                                        put("avatarUrl", selectedAvatar)
                                        put("icon", selectedAvatar)
                                    }.toMap()

                                    onSysttsChange(
                                        systts.copy(
                                            displayName =
                                                if (systts.displayName.isNullOrBlank() || lastName == systts.displayName) {
                                                    selectedName
                                                } else {
                                                    systts.displayName
                                                },
                                            config = (systts.config as TtsConfigurationDTO).copy(
                                                source = tts.copy(
                                                    voice = selectedVoice,
                                                    data = newData
                                                )
                                            )
                                        )
                                    )

                                    runCatching {
                                        realVm.updateCustomUI(tts.locale, selectedVoice)
                                    }.onFailure {
                                        context.displayErrorDialog(it)
                                    }

                                    displayName = selectedName
                                }
                            )

                            suspend fun load(linearLayout: LinearLayout) {
                                runCatching {
                                    realVm.load(context, currentPlugin, tts, linearLayout)
                                }.onFailure {
                                    it.printStackTrace()
                                    context.displayErrorDialog(it)
                                }
                            }

                            val customUiKey = listOf(
                                currentPlugin.pluginId,
                                tts.locale,
                                tts.voice,
                                tts.data.hashCode().toString()
                            ).joinToString("|")

                            key(customUiKey) {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(),
                                    factory = {
                                        LinearLayout(it).apply {
                                            orientation = LinearLayout.VERTICAL
                                            scope.launch {
                                                load(this@apply)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            ParamsEditScreen(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                systemTts = systts,
                onSystemTtsChange = onSysttsChange
            )
        }
    }
}

