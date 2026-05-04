package com.github.jing332.tts_server_android.compose.systts.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.jing332.tts_server_android.R

@Composable
fun FloatingAddConfigButtonGroup(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    addBgm: () -> Unit,
    addLocal: () -> Unit,
    addPlugin: () -> Unit,
    addGroup: () -> Unit,
) {
    var expended by rememberSaveable { mutableStateOf(false) }

    BackHandler(expended) {
        expended = false
    }

    if (!visible && !expended) return

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(horizontalAlignment = Alignment.End) {
            if (expended) {
                AddMenuItem(
                    onClick = {
                        addLocal()
                        expended = false
                    },
                    text = stringResource(R.string.add_local_tts),
                    icon = { Icon(Icons.Default.PhoneAndroid, null) }
                )

                Spacer(Modifier.height(6.dp))

                AddMenuItem(
                    onClick = {
                        addPlugin()
                        expended = false
                    },
                    text = stringResource(R.string.systts_add_plugin_tts),
                    icon = { Icon(Icons.Default.Javascript, null) }
                )

                Spacer(Modifier.height(6.dp))

                AddMenuItem(
                    onClick = {
                        addBgm()
                        expended = false
                    },
                    text = stringResource(R.string.add_bgm_tts),
                    icon = { Icon(Icons.Default.MusicNote, null) }
                )

                Spacer(Modifier.height(6.dp))

                AddMenuItem(
                    onClick = {
                        addGroup()
                        expended = false
                    },
                    text = stringResource(R.string.add_group),
                    icon = { Icon(Icons.Default.AddCard, null) }
                )

                Spacer(Modifier.height(10.dp))
            }

            FloatingActionButton(
                onClick = {
                    expended = !expended
                }
            ) {
                Icon(
                    imageVector = if (expended) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
private fun AddMenuItem(
    onClick: () -> Unit,
    text: String,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
}
