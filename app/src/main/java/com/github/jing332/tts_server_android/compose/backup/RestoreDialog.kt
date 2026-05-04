package com.github.jing332.tts_server_android.compose.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.jing332.common.utils.FileUtils.readBytes
import com.github.jing332.compose.widgets.AppDialog
import com.github.jing332.compose.widgets.LoadingContent
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.FilePickerActivity
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import kotlinx.coroutines.launch

@Composable
internal fun RestoreDialog(
    onDismissRequest: () -> Unit,
    vm: BackupRestoreViewModel = viewModel(),
) {
    var isLoading by remember { mutableStateOf(true) }
    var needRestart by remember { mutableStateOf(false) }
    var pendingRestoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var hasRestored by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun restoreWithMode(mode: SpeechRuleImportMode) {
        val bytes = pendingRestoreBytes ?: return

        pendingRestoreBytes = null
        isLoading = true

        scope.launch {
            runCatching {
                vm.speechRuleImportMode = mode
                needRestart = vm.restore(bytes)
                hasRestored = true
                isLoading = false
            }.onFailure {
                isLoading = false
                context.displayErrorDialog(it)
            }
        }
    }

    val filePicker =
        rememberLauncherForActivityResult(
            contract = AppActivityResultContracts.filePickerActivity()
        ) {
            if (it.second == null) {
                onDismissRequest()
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                runCatching {
                    pendingRestoreBytes = it.second!!.readBytes(context)
                    isLoading = false
                }.onFailure {
                    isLoading = false
                    context.displayErrorDialog(it)
                }
            }
        }

    LaunchedEffect(Unit) {
        filePicker.launch(FilePickerActivity.RequestSelectFile(listOf("application/zip")))
    }

    AppDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(id = R.string.restore))
        },
        content = {
            LoadingContent(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                isLoading = isLoading
            ) {
                when {
                    pendingRestoreBytes != null -> {
                        Column {
                            Text("请选择朗读规则导入方式：")
                            Text(
                                text = "\n1. 覆盖更新：同 ruleId 覆盖旧规则。" +
                                        "\n\n2. 修改ID：自动生成新 ruleId，例如 mingwuyan_manual_001。" +
                                        "\n\n3. 同基础ID共存：自动生成 mingwuyan_dev_001，并继续共用角色列表和密钥目录。"
                            )
                        }
                    }

                    hasRestored && needRestart -> {
                        Text(stringResource(id = R.string.restore_restart_msg))
                    }

                    hasRestored -> {
                        Text(stringResource(id = R.string.restore_finished))
                    }

                    else -> {
                        Text("请选择备份文件")
                    }
                }
            }
        },
        buttons = {
            when {
                pendingRestoreBytes != null -> {
                    TextButton(
                        onClick = {
                            restoreWithMode(SpeechRuleImportMode.OverwriteUpdate)
                        }
                    ) {
                        Text("覆盖更新")
                    }

                    TextButton(
                        onClick = {
                            restoreWithMode(SpeechRuleImportMode.RenameId)
                        }
                    ) {
                        Text("修改ID")
                    }

                    TextButton(
                        onClick = {
                            restoreWithMode(SpeechRuleImportMode.SameBaseCoexist)
                        }
                    ) {
                        Text("同基础ID共存")
                    }
                }

                needRestart -> {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(id = R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            app.restart()
                        }
                    ) {
                        Text(stringResource(id = R.string.restart))
                    }
                }

                else -> {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(id = R.string.confirm))
                    }
                }
            }
        }
    )
}