package com.github.jing332.tts_server_android.compose.systts.list

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AvatarPickerDialog(
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val avatars = remember { loadAvatarLibrary(context) }
    val grouped = remember(avatars) {
        avatars.groupBy { it.gender.ifBlank { "未分类" } }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("选择头像") },
        text = {
            if (avatars.isEmpty()) {
                Text("头像库为空或读取失败")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(58.dp),
                    modifier = Modifier.heightIn(max = 460.dp)
                ) {
                    grouped.forEach { (gender, list) ->
                        item(
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Text(
                                text = gender,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                            )
                        }

                        items(
                            items = list,
                            key = { it.iconUrl }
                        ) { avatar ->
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onSelect(avatar.iconUrl)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = avatar.iconUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReset) {
                Text("恢复默认头像")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}
