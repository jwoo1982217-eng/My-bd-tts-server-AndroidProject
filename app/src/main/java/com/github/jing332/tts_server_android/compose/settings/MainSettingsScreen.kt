package com.github.jing332.tts_server_android.compose.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.jing332.tts_server_android.AppLocale
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.compose.backup.BackupRestoreActivity
import com.github.jing332.tts_server_android.compose.nav.NavTopAppBar
import com.github.jing332.tts_server_android.compose.systts.directlink.LinkUploadRuleActivity
import com.github.jing332.tts_server_android.compose.theme.getAppTheme
import com.github.jing332.tts_server_android.compose.theme.setAppTheme
import com.github.jing332.tts_server_android.conf.AppConfig
import com.github.jing332.tts_server_android.constant.FilePickerMode
import com.github.jing332.tts_server_android.utils.MyTools.isIgnoringBatteryOptimizations
import com.github.jing332.tts_server_android.utils.MyTools.killBattery
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.github.jing332.common.utils.toast
import com.github.jing332.tts_server_android.service.systts.help.AudioCacheFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var showThemeDialog by remember { mutableStateOf(false) }
    if (showThemeDialog)
        ThemeSelectionDialog(
            onDismissRequest = { showThemeDialog = false },
            currentTheme = getAppTheme(),
            onChangeTheme = {
                setAppTheme(it)
            }
        )

    val scrollBehaviour = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.nestedScroll(scrollBehaviour.nestedScrollConnection),
        topBar = {
            NavTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                scrollBehavior = scrollBehaviour,
            )
        }
    ) { paddingValues ->
        val context = LocalContext.current

          val scope = rememberCoroutineScope()
          var showAudioCacheDialog by remember { mutableStateOf(false) }
          var showAudioCacheBookDialog by remember { mutableStateOf(false) }
          var showAudioCacheChapterDialog by remember { mutableStateOf(false) }
          var selectedAudioCacheBookKey by remember { mutableStateOf("") }
          var audioCacheStat by remember {
              mutableStateOf(AudioCacheFactory.AudioCacheStat(0L, 0, 0L, 0))
          }
          var audioCacheChapters by remember {
              mutableStateOf<List<AudioCacheFactory.AudioChapterCacheInfo>>(emptyList())
          }

          fun refreshAudioCacheInfo() {
              scope.launch {
                  audioCacheStat = withContext(Dispatchers.IO) {
                      AudioCacheFactory.getAudioCacheStat(context)
                  }
                  audioCacheChapters = withContext(Dispatchers.IO) {
                      AudioCacheFactory.listAudioChapterCaches(context)
                  }
              }
          }

          fun clearAudioCache(typeName: String, extensions: Set<String>) {
              scope.launch {
                  val count = withContext(Dispatchers.IO) {
                      AudioCacheFactory.clearAudioCacheByExtensions(context, extensions)
                  }
                  refreshAudioCacheInfo()
                  context.toast("已清理${count}个${typeName}音频缓存")
              }
          }

          fun clearBookAudioCache(bookKey: String, typeName: String, extensions: Set<String>) {
              scope.launch {
                  val count = withContext(Dispatchers.IO) {
                      AudioCacheFactory.clearAudioBookCacheByExtensions(context, bookKey, extensions)
                  }
                  refreshAudioCacheInfo()
                  context.toast("已清理${count}个${typeName}音频缓存")
              }
          }

          fun clearChapterAudioCache(bookKey: String, chapterKey: String, typeName: String, extensions: Set<String>) {
              scope.launch {
                  val count = withContext(Dispatchers.IO) {
                      AudioCacheFactory.clearAudioChapterCacheByExtensions(context, bookKey, chapterKey, extensions)
                  }
                  refreshAudioCacheInfo()
                  context.toast("已清理${count}个${typeName}音频缓存")
              }
          }

          // 不在进入设置页时自动扫描缓存，避免大量文件导致 UI 卡顿。
          // 点击“音频缓存”时再后台刷新。


          if (showAudioCacheDialog) {
              AlertDialog(
                  onDismissRequest = { showAudioCacheDialog = false },
                  title = { Text("音频缓存清理") },
                  text = {
                      Column {
                          val audioCacheLimit = 80L * 1024L * 1024L
                          val totalAudioCacheBytes = audioCacheStat.pcmBytes + audioCacheStat.mp3Bytes
                          val warningColor = if (totalAudioCacheBytes >= audioCacheLimit) Color.Red else Color.Unspecified

                          Text(
                              text = "总计：${AudioCacheFactory.formatAudioCacheSize(totalAudioCacheBytes)} / 80 MB",
                              color = warningColor
                          )
                          Text(
                              text = "PCM：${AudioCacheFactory.formatAudioCacheSize(audioCacheStat.pcmBytes)}，${audioCacheStat.pcmCount} 个",
                              color = warningColor
                          )
                          Text(
                              text = "MP3：${AudioCacheFactory.formatAudioCacheSize(audioCacheStat.mp3Bytes)}，${audioCacheStat.mp3Count} 个",
                              color = warningColor
                          )

                          TextButton(onClick = { refreshAudioCacheInfo() }) {
                              Text("刷新缓存大小")
                          }

                          TextButton(onClick = { clearAudioCache("PCM", setOf("pcm")) }) {
                              Text("清理全部 PCM")
                          }

                          TextButton(onClick = { clearAudioCache("MP3", setOf("mp3")) }) {
                              Text("清理全部 MP3")
                          }

                          TextButton(
                              enabled = audioCacheChapters.isNotEmpty(),
                              onClick = {
                                  refreshAudioCacheInfo()
                                  showAudioCacheBookDialog = true
                              }
                          ) {
                              Text("按书籍清理")
                          }

                          TextButton(
                              enabled = audioCacheChapters.isNotEmpty(),
                              onClick = {
                                  refreshAudioCacheInfo()
                                  selectedAudioCacheBookKey = ""
                                  showAudioCacheChapterDialog = true
                              }
                          ) {
                              Text("按章节清理")
                          }
                      }
                  },
                  confirmButton = {},
                  dismissButton = {
                      TextButton(onClick = { showAudioCacheDialog = false }) {
                          Text("关闭")
                      }
                  }
              )
          }

          if (showAudioCacheBookDialog) {
              AlertDialog(
                  onDismissRequest = { showAudioCacheBookDialog = false },
                  title = { Text("按书籍清理音频缓存") },
                  text = {
                      Column {
                          val groups = audioCacheChapters.groupBy { it.bookKey }
                          groups.forEach { entry ->
                              val bookKey = entry.key
                              val list = entry.value
                              val bookName = list.firstOrNull()?.bookName ?: bookKey
                              val pcmBytes = list.sumOf { it.pcmBytes }
                              val pcmCount = list.sumOf { it.pcmCount }
                              val mp3Bytes = list.sumOf { it.mp3Bytes }
                              val mp3Count = list.sumOf { it.mp3Count }

                              TextButton(
                                  enabled = pcmCount > 0,
                                  onClick = { clearBookAudioCache(bookKey, "本书PCM", setOf("pcm")) }
                              ) {
                                  Text("$bookName｜PCM ${AudioCacheFactory.formatAudioCacheSize(pcmBytes)}，${pcmCount}个")
                              }

                              TextButton(
                                  enabled = mp3Count > 0,
                                  onClick = { clearBookAudioCache(bookKey, "本书MP3", setOf("mp3")) }
                              ) {
                                  Text("$bookName｜MP3 ${AudioCacheFactory.formatAudioCacheSize(mp3Bytes)}，${mp3Count}个")
                              }
                          }
                      }
                  },
                  confirmButton = {},
                  dismissButton = {
                      TextButton(onClick = { showAudioCacheBookDialog = false }) {
                          Text("关闭")
                      }
                  }
              )
          }

          if (showAudioCacheChapterDialog) {
              AlertDialog(
                  onDismissRequest = { showAudioCacheChapterDialog = false },
                  title = { Text("按章节清理音频缓存") },
                  text = {
                      Column {
                          if (selectedAudioCacheBookKey.isBlank()) {
                              val groups = audioCacheChapters.groupBy { it.bookKey }
                              groups.forEach { entry ->
                                  val bookKey = entry.key
                                  val bookName = entry.value.firstOrNull()?.bookName ?: bookKey
                                  TextButton(onClick = { selectedAudioCacheBookKey = bookKey }) {
                                      Text(bookName)
                                  }
                              }
                          } else {
                              audioCacheChapters
                                  .filter { it.bookKey == selectedAudioCacheBookKey }
                                  .take(80)
                                  .forEach { chapter ->
                                      val title = if (chapter.chapterTitle.isBlank()) {
                                          "第${chapter.chapterIndex}章"
                                      } else {
                                          "${chapter.chapterIndex} ${chapter.chapterTitle}"
                                      }

                                      TextButton(
                                          enabled = chapter.pcmCount > 0,
                                          onClick = {
                                              clearChapterAudioCache(
                                                  chapter.bookKey,
                                                  chapter.chapterKey,
                                                  "本章PCM",
                                                  setOf("pcm")
                                              )
                                          }
                                      ) {
                                          Text("$title｜PCM ${AudioCacheFactory.formatAudioCacheSize(chapter.pcmBytes)}，${chapter.pcmCount}个")
                                      }

                                      TextButton(
                                          enabled = chapter.mp3Count > 0,
                                          onClick = {
                                              clearChapterAudioCache(
                                                  chapter.bookKey,
                                                  chapter.chapterKey,
                                                  "本章MP3",
                                                  setOf("mp3")
                                              )
                                          }
                                      ) {
                                          Text("$title｜MP3 ${AudioCacheFactory.formatAudioCacheSize(chapter.mp3Bytes)}，${chapter.mp3Count}个")
                                      }
                                  }
                          }
                      }
                  },
                  confirmButton = {},
                  dismissButton = {
                      TextButton(
                          onClick = {
                              if (selectedAudioCacheBookKey.isNotBlank()) {
                                  selectedAudioCacheBookKey = ""
                              } else {
                                  showAudioCacheChapterDialog = false
                              }
                          }
                      ) {
                          Text(if (selectedAudioCacheBookKey.isNotBlank()) "返回书籍" else "关闭")
                      }
                  }
              )
          }
        Column(
            Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            DividerPreference { Text(stringResource(id = R.string.app_name)) }

            val showBatteryOptimization =
                rememberUpdatedState(!context.isIgnoringBatteryOptimizations())

            if (showBatteryOptimization.value)
                BasePreferenceWidget(
                    onClick = { context.killBattery() },
                    title = { Text(stringResource(id = R.string.battery_optimization_whitelist)) },
                    subTitle = { Text(stringResource(R.string.battery_optimization_whitelist_desc)) },
                    icon = { Icon(Icons.Default.BatteryFull, null) }
                )

            BasePreferenceWidget(
                icon = {
                    Icon(Icons.Default.SettingsBackupRestore, null)
                },
                onClick = {
                    context.startActivity(
                        Intent(
                            context,
                            BackupRestoreActivity::class.java
                        ).apply { action = Intent.ACTION_VIEW })
                },
                title = { Text(stringResource(id = R.string.backup_restore)) },
            )

            BasePreferenceWidget(
                icon = {
                    Icon(Icons.Default.Link, null)
                },
                onClick = {
                    context.startActivity(
                        Intent(
                            context, LinkUploadRuleActivity::class.java
                        ).apply { action = Intent.ACTION_VIEW })
                },
                title = { Text(stringResource(id = R.string.direct_link_settings)) },
            )

            BasePreferenceWidget(
                icon = { Icon(Icons.Default.ColorLens, null) },
                onClick = { showThemeDialog = true },
                title = { Text(stringResource(id = R.string.theme)) },
                subTitle = { Text(stringResource(id = getAppTheme().stringResId)) },
            )

            val languageKeys = remember {
                mutableListOf("").apply { addAll(AppLocale.localeMap.keys.toList()) }
            }

            val languageNames = remember {
                AppLocale.localeMap.map { "${it.value.displayName} - ${it.value.getDisplayName(it.value)}" }
                    .toMutableList()
                    .apply { add(0, context.getString(R.string.follow_system)) }
            }

            var langMenu by remember { mutableStateOf(false) }
            DropdownPreference(
                Modifier.minimumInteractiveComponentSize(),
                expanded = langMenu,
                onExpandedChange = { langMenu = it },
                icon = {
                    Icon(Icons.Default.Language, null)
                },
                title = { Text(stringResource(id = R.string.language)) },
                subTitle = {
                    Text(
                        if (AppLocale.getLocaleCodeFromFile(context).isEmpty()) {
                            stringResource(id = R.string.follow_system)
                        } else {
                            AppLocale.getLocaleFromFile(context).displayName
                        }
                    )
                }) {
                languageNames.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = {
                            Text(name)
                        }, onClick = {
                            langMenu = false

                            AppLocale.saveLocaleCodeToFile(context, languageKeys[index])
                            AppLocale.setLocale(app)
                        }
                    )
                }
            }

            var filePickerMode by remember { AppConfig.filePickerMode }
            var expanded by remember { mutableStateOf(false) }
            DropdownPreference(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                icon = { Icon(Icons.Default.FileOpen, null) },
                title = { Text(stringResource(id = R.string.file_picker_mode)) },
                subTitle = {
                    Text(
                        when (filePickerMode) {
                            FilePickerMode.PROMPT -> stringResource(id = R.string.file_picker_mode_prompt)
                            FilePickerMode.BUILTIN -> stringResource(id = R.string.file_picker_mode_builtin)
                            else -> stringResource(id = R.string.file_picker_mode_system)
                        }
                    )
                },
                actions = {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.file_picker_mode_prompt)) },
                        onClick = {
                            expanded = false
                            filePickerMode = FilePickerMode.PROMPT
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.file_picker_mode_builtin)) },
                        onClick = {
                            expanded = false
                            filePickerMode = FilePickerMode.BUILTIN
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.file_picker_mode_system)) },
                        onClick = {
                            expanded = false
                            filePickerMode = FilePickerMode.SYSTEM
                        }
                    )
                }
            )

            var autoCheck by remember { AppConfig.isAutoCheckUpdateEnabled }
            SwitchPreference(
                title = { Text(stringResource(id = R.string.auto_check_update)) },
                subTitle = { Text(stringResource(id = R.string.check_update_summary)) },
                checked = autoCheck,
                onCheckedChange = { autoCheck = it },
                icon = {
                    Icon(Icons.Default.ArrowCircleUp, contentDescription = null)
                }
            )

            var excludeFromRecent by remember { AppConfig.isExcludeFromRecent }
            SwitchPreference(
                title = { Text(stringResource(id = R.string.exclude_from_recent)) },
                subTitle = { Text(stringResource(id = R.string.exclude_from_recent_summary)) },
                checked = excludeFromRecent,
                onCheckedChange = { excludeFromRecent = it },
                icon = {
                    Icon(Icons.Default.HideSource, contentDescription = null)
                }
            )


              val audioCacheLimitBytes = 80L * 1024L * 1024L
              val totalAudioCacheBytes = audioCacheStat.pcmBytes + audioCacheStat.mp3Bytes
              val audioCacheWarningColor =
                  if (totalAudioCacheBytes >= audioCacheLimitBytes) Color.Red else Color.Unspecified

              BasePreferenceWidget(
                  icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                  onClick = {
                      refreshAudioCacheInfo()
                      showAudioCacheDialog = true
                  },
                  title = {
                      Text(
                          text = "音频缓存",
                          color = audioCacheWarningColor
                      )
                  },
                  subTitle = {
                      Column {
                          Text(
                              text = "总计：${AudioCacheFactory.formatAudioCacheSize(totalAudioCacheBytes)} / 80 MB",
                              color = audioCacheWarningColor
                          )
                          Text(
                              text = "PCM：${AudioCacheFactory.formatAudioCacheSize(audioCacheStat.pcmBytes)}，${audioCacheStat.pcmCount} 个",
                              color = audioCacheWarningColor
                          )
                          Text(
                              text = "MP3：${AudioCacheFactory.formatAudioCacheSize(audioCacheStat.mp3Bytes)}，${audioCacheStat.mp3Count} 个",
                              color = audioCacheWarningColor
                          )
                      }
                  }
              )
            var maxDropdownCount by remember { AppConfig.spinnerMaxDropDownCount }
            SliderPreference(
                title = { Text(stringResource(id = R.string.spinner_drop_down_max_count)) },
                subTitle = { Text(stringResource(id = R.string.spinner_drop_down_max_count_summary)) },
                value = maxDropdownCount.toFloat(),
                onValueChange = { maxDropdownCount = it.toInt() },
                label = if (maxDropdownCount == 0) stringResource(id = R.string.unlimited) else maxDropdownCount.toString(),
                valueRange = 0f..50f,
                icon = { Icon(Icons.AutoMirrored.Filled.MenuOpen, null) }
            )

            SysttsSettingsScreen()
            OtherSettingsScreen()


            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
