package com.github.jing332.tts_server_android.compose.systts.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import com.github.jing332.compose.widgets.TextFieldDialog
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.systts.GroupItem

@Composable
fun Group(
    modifier: Modifier,
    name: String,
    isExpanded: Boolean,
    toggleableState: ToggleableState,
    onToggleableStateChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: (newName: String) -> Unit,
    onCopy: (newName: String) -> Unit,
    onEditAudioParams: () -> Unit,
    onSort: () -> Unit,
    onAddChildGroup: (() -> Unit)? = null,
    onPromoteToRoot: (() -> Unit)? = null,
    onMoveToParent: (() -> Unit)? = null,
    onEditGroupMembers: (() -> Unit)? = null,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    if (showRenameDialog) {
        var nameValue by remember { mutableStateOf(name) }
        TextFieldDialog(
            title = stringResource(id = R.string.rename),
            text = nameValue,
            onTextChange = { nameValue = it },
            onDismissRequest = { showRenameDialog = false }
        ) {
            showRenameDialog = false
            onRename(nameValue)
        }
    }

    var showCopyDialog by remember { mutableStateOf(false) }
    if (showCopyDialog) {
        var nameValue by remember { mutableStateOf(name) }
        TextFieldDialog(
            title = stringResource(id = R.string.copy),
            text = nameValue,
            onTextChange = { nameValue = it },
            onDismissRequest = { showCopyDialog = false }
        ) {
            showCopyDialog = false
            onCopy(nameValue)
        }
    }

    val context = LocalContext.current

    GroupItem(
        modifier = modifier.semantics {
            customActions = listOfNotNull(
                CustomAccessibilityAction(context.getString(R.string.rename)) {
                    showRenameDialog = true
                    true
                },
                CustomAccessibilityAction(context.getString(R.string.copy)) {
                    showCopyDialog = true
                    true
                },
                onEditGroupMembers?.let {
                    CustomAccessibilityAction("编辑分组") {
                        it()
                        true
                    }
                },
                CustomAccessibilityAction(context.getString(R.string.audio_params)) {
                    onEditAudioParams()
                    true
                },
                CustomAccessibilityAction(context.getString(R.string.sort)) {
                    onSort()
                    true
                },
                CustomAccessibilityAction(context.getString(R.string.delete)) {
                    onDelete()
                    true
                },
                CustomAccessibilityAction(context.getString(R.string.export_config)) {
                    onExport()
                    true
                }
            )
        },
        isExpanded = isExpanded,
        name = name,
        toggleableState = toggleableState,
        onToggleableStateChange = onToggleableStateChange,
        onClick = onClick,
        onExport = onExport,
        onDelete = onDelete,
        actions = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.rename)) },
                onClick = {
                    dismiss()
                    showRenameDialog = true
                },
                leadingIcon = {
                    Icon(Icons.Default.DriveFileRenameOutline, null)
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.copy)) },
                onClick = {
                    dismiss()
                    showCopyDialog = true
                },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, null)
                }
            )

            if (onEditGroupMembers != null) {
                DropdownMenuItem(
                    text = { Text("编辑分组") },
                    onClick = {
                        dismiss()
                        onEditGroupMembers()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.CreateNewFolder, null)
                    }
                )
            }

            if (onAddChildGroup != null) {
                DropdownMenuItem(
                    text = { Text("📥 新建子目录") },
                    onClick = {
                        dismiss()
                        onAddChildGroup()
                    }
                )
            }

            if (onPromoteToRoot != null) {
                DropdownMenuItem(
                    text = { Text("⬆️ 升级为一级目录") },
                    onClick = {
                        dismiss()
                        onPromoteToRoot()
                    }
                )
            }

            if (onMoveToParent != null) {
                DropdownMenuItem(
                    text = { Text("⬇️ 降级到其他一级目录") },
                    onClick = {
                        dismiss()
                        onMoveToParent()
                    }
                )
            }

            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.audio_params)) },
                onClick = {
                    dismiss()
                    onEditAudioParams()
                },
                leadingIcon = {
                    Icon(Icons.Default.Speed, null)
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.sort)) },
                onClick = {
                    dismiss()
                    onSort()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Default.Sort, null)
                }
            )
        }
    )
}